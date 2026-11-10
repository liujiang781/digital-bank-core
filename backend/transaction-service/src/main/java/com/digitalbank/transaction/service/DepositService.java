package com.digitalbank.transaction.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.transaction.channel.MockChannelService;
import com.digitalbank.transaction.channel.MockChannelService.ChannelRow;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.client.RiskClient;
import com.digitalbank.transaction.dto.RiskDtos.AccountView;
import com.digitalbank.transaction.dto.RiskDtos.CheckRequest;
import com.digitalbank.transaction.dto.RiskDtos.Decision;
import com.digitalbank.transaction.dto.RiskDtos.PayeeView;
import com.digitalbank.transaction.dto.TransactionDtos.AccountSnapshot;
import com.digitalbank.transaction.dto.TransactionDtos.Command;
import com.digitalbank.transaction.dto.TransactionDtos.DepositResult;
import com.digitalbank.transaction.dto.TransactionDtos.Failure;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.Snapshot;
import com.digitalbank.transaction.dto.TransactionDtos.SnapshotRequest;
import com.digitalbank.transaction.dto.TransactionDtos.TradeView;
import com.digitalbank.transaction.security.Actor;
import com.digitalbank.transaction.support.Fingerprints;
import com.digitalbank.transaction.support.Money;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 存款受理与编排（交接文档第 4 节固定顺序）：
 * 认证与快照归属校验 → 三元组认领原单 → 风控 → 账户侧受理 → 模拟渠道 → 渠道结果上报。
 * 只有首次认领原单的一方启动首次编排；重复请求只返回当前结果，未完成步骤由恢复扫描推进。
 * 跨 HTTP 调用不放进一个数据库事务，也不把 @Transactional 当成跨服务事务。
 */
@Service
public class DepositService {
    private static final Logger log = LoggerFactory.getLogger(DepositService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "REJECTED");

    private final IdempotencyService idempotency;
    private final AccountClient account;
    private final RiskClient risk;
    private final MockChannelService channel;

    public DepositService(IdempotencyService idempotency, AccountClient account,
            RiskClient risk, MockChannelService channel) {
        this.idempotency = idempotency;
        this.account = account;
        this.risk = risk;
        this.channel = channel;
    }

    public TradeView deposit(Actor actor, String accountId, String amountText, String purpose,
            String requestId, String traceId) {
        BigDecimal amount = Money.parsePositive(amountText);
        // 对象权限先于认领：S2 快照接口复核账户归属与状态上下文
        account.snapshot(new SnapshotRequest(actor.identityId(), "DEPOSIT", accountId, null, null), traceId);
        String requestHash = Fingerprints.requestHash("DEPOSIT", accountId, "", "",
                Money.format(amount), "CNY", purpose);
        IdempotencyService.Claim claim = idempotency.claim(actor.identityId(), "DEPOSIT", requestId,
                requestHash, accountId, amount, "CNY");
        if (!claim.isNew()) {
            // 重复请求命中已有原单：只返回当前结果，不再次启动渠道调用
            return currentView(claim.tradeNo(), traceId);
        }
        advance(claim.tradeNo(), actor.identityId(), accountId, amount, traceId);
        return currentView(claim.tradeNo(), traceId);
    }

    /** 恢复扫描入口：ACCEPTED 原单继续风控和账户侧受理；所有业务拒绝已持久化，恢复过程只记录日志。 */
    public void resume(IdempotencyService.TradeRow row, String traceId) {
        try {
            advance(row.tradeNo(), row.identityId(), row.accountId(), row.amount(), traceId);
        } catch (BizException e) {
            log.warn("恢复编排未完成，等待下一轮扫描: tradeNo={}, status={}", row.tradeNo(), row.status(), e);
        }
    }

    /** 渠道结果上报（SUCCESS/FAILED 明确推进；UNKNOWN 保持待确认），供查询与恢复复用，幂等。 */
    public void reportChannelResult(String tradeNo, String accountId, BigDecimal amount,
            ChannelRow record, String traceId) {
        try {
            account.depositResult(new DepositResult(tradeNo, accountId, Money.format(amount), "CNY",
                    record.result(), record.channelRequestId(), record.evidenceId(),
                    OffsetDateTime.now(ZONE), record.reasonCode()), tradeNo, traceId);
        } catch (BizException e) {
            log.warn("渠道结果上报未完成，恢复任务会沿原单重试: tradeNo={}, result={}",
                    tradeNo, record.result(), e);
        }
    }

    public TradeView currentView(String tradeNo, String traceId) {
        try {
            Outcome outcome = account.outcome(tradeNo, traceId);
            return new TradeView(outcome.tradeNo(), outcome.status(), outcome.reasonCode());
        } catch (BizException unreachable) {
            IdempotencyService.TradeRow row = idempotency.readTrade(tradeNo);
            if (row == null) throw new BizException(ErrorCode.TRADE_NOT_FOUND, "交易不存在");
            return new TradeView(row.tradeNo(), row.status(), row.failureReason());
        }
    }

    /**
     * 编排核心：风控 → 账户侧受理 → 模拟渠道 → 结果上报。
     * 风控拒绝对外抛 422；其余下游暂不可用时保留原单当前状态并交由恢复扫描继续。
     */
    private void advance(String tradeNo, String identityId, String accountId, BigDecimal amount, String traceId) {
        Decision decision;
        try {
            Snapshot snapshot = account.snapshot(
                    new SnapshotRequest(identityId, "DEPOSIT", accountId, null, null), traceId);
            decision = risk.check(checkRequest(tradeNo, identityId, amount, snapshot.account()), traceId);
            if ("REJECT".equals(decision.result())) {
                account.recordFailure(new Failure(tradeNo, "REJECTED", decision.reasonCode(),
                        "RISK_EVENT", decision.eventId()), tradeNo, traceId);
                throw new BizException(toErrorCode(decision.reasonCode()), "风控拒绝: " + decision.reasonCode());
            }
        } catch (BizException e) {
            if (e.getErrorCode() != ErrorCode.INTERNAL_ERROR) throw e; // 风控拒绝直接对外
            log.warn("快照或风控暂不可用，原单保留当前状态: tradeNo={}", tradeNo, e);
            return;
        }
        Outcome outcome;
        try {
            outcome = account.acceptDeposit(
                    new Command(tradeNo, null, null, accountId, Money.format(amount), "CNY"),
                    tradeNo, traceId);
        } catch (BizException e) {
            log.warn("账户侧受理未完成，原单保留当前状态: tradeNo={}", tradeNo, e);
            return;
        }
        if ("REJECTED".equals(outcome.status())) {
            // S2 已在本地事务持久化拒绝状态；对外映射相应业务错误
            throw new BizException(toErrorCode(outcome.reasonCode()),
                    "交易被拒绝: " + outcome.reasonCode());
        }
        if (!"PROCESSING".equals(outcome.status())) return; // 终态或已未知，交由查询/恢复收敛
        ChannelRow record = channel.createOrGet(tradeNo, accountId, amount, "CNY");
        reportChannelResult(tradeNo, accountId, amount, record, traceId);
    }

    public CheckRequest checkRequest(String tradeNo, String identityId, BigDecimal amount,
            AccountSnapshot accountSnapshot) {
        AccountView view = new AccountView(accountSnapshot.accountId(), accountSnapshot.status(),
                accountSnapshot.effectiveSingleLimit(), accountSnapshot.effectiveDailyLimit(),
                accountSnapshot.dailyUsedAmount());
        return new CheckRequest(tradeNo, identityId, "DEPOSIT", Money.format(amount), "CNY", view, (PayeeView) null);
    }

    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }

    private ErrorCode toErrorCode(String reasonCode) {
        try {
            return ErrorCode.valueOf(reasonCode);
        } catch (IllegalArgumentException unknown) {
            return ErrorCode.ACCOUNT_STATUS_INVALID;
        }
    }
}
