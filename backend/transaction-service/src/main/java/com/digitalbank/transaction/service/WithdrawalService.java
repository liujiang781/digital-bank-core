package com.digitalbank.transaction.service;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.client.RiskClient;
import com.digitalbank.transaction.dto.RiskDtos.AccountView;
import com.digitalbank.transaction.dto.RiskDtos.CheckRequest;
import com.digitalbank.transaction.dto.RiskDtos.Decision;
import com.digitalbank.transaction.dto.RiskDtos.PayeeView;
import com.digitalbank.transaction.dto.TransactionDtos.AccountSnapshot;
import com.digitalbank.transaction.dto.TransactionDtos.Failure;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.Snapshot;
import com.digitalbank.transaction.dto.TransactionDtos.SnapshotRequest;
import com.digitalbank.transaction.dto.TransactionDtos.TradeView;
import com.digitalbank.transaction.security.Actor;
import com.digitalbank.transaction.service.IdempotencyService.Claim;
import com.digitalbank.transaction.service.IdempotencyService.TradeRow;
import com.digitalbank.transaction.support.Fingerprints;
import com.digitalbank.transaction.support.Money;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 取款受理与编排（交接文档第 6 节固定顺序）：
 * 认领原单 → 风控（出账单笔/日累计）→ S2 reserve → S2 withdrawal-completions。
 * 取款是本地业务，不经模拟渠道；失败只按明确的本地失败依据走 S2 releases，
 * 超时或未知不得作为释放依据。只有首次认领原单的一方启动首次编排；
 * 重复请求只返回当前结果，未完成步骤由恢复扫描沿原 tradeNo 推进。
 */
@Service
public class WithdrawalService {
    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final IdempotencyService idempotency;
    private final AccountClient account;
    private final RiskClient risk;
    private final TradeStateReader stateReader;

    public WithdrawalService(IdempotencyService idempotency, AccountClient account,
            RiskClient risk, TradeStateReader stateReader) {
        this.idempotency = idempotency;
        this.account = account;
        this.risk = risk;
        this.stateReader = stateReader;
    }

    public TradeView withdraw(Actor actor, String accountId, String amountText, String currency,
            String purpose, String requestId, String traceId) {
        if (currency != null && !"CNY".equals(currency)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "币种固定为 CNY");
        }
        BigDecimal amount = Money.parsePositive(amountText);
        // 对象权限先于认领：S2 快照复核付款账户归属与状态上下文（取款用 payerAccountId 定位账户）
        account.snapshot(new SnapshotRequest(actor.identityId(), "WITHDRAWAL", accountId, accountId, null),
                traceId);
        String requestHash = Fingerprints.requestHash("WITHDRAWAL", accountId, accountId, "",
                Money.format(amount), "CNY", purpose);
        Claim claim = idempotency.claim(actor.identityId(), "WITHDRAWAL", requestId, requestHash,
                accountId, amount, "CNY");
        if (!claim.isNew()) {
            // 重复请求命中已有原单：只返回当前结果，不再次启动编排
            return stateReader.currentView(claim.tradeNo(), traceId);
        }
        advance(claim.tradeNo(), actor.identityId(), accountId, amount, traceId);
        return stateReader.currentView(claim.tradeNo(), traceId);
    }

    /** 恢复扫描入口：ACCEPTED 原单继续风控与账户侧预留；业务拒绝已持久化，恢复过程只记录日志。 */
    public void resume(TradeRow row, String traceId) {
        try {
            advance(row.tradeNo(), row.identityId(), row.accountId(), row.amount(), traceId);
        } catch (BizException e) {
            log.warn("取款恢复编排未完成，等待下一轮扫描: tradeNo={}, status={}", row.tradeNo(), row.status(), e);
        }
    }

    /** 恢复扫描入口：PROCESSING 原单先查资金结果，非终态则幂等重发提交命令。 */
    public void resumeProcessing(TradeRow row, String traceId) {
        try {
            Outcome outcome = account.outcome(row.tradeNo(), traceId);
            if (DepositService.isTerminal(outcome.status())) return;
        } catch (BizException e) {
            log.warn("查询取款资金结果失败，等待下一轮: tradeNo={}", row.tradeNo(), e);
            return;
        }
        try {
            account.withdrawalCompletion(
                    OutgoingCommands.command(row.tradeNo(), row.accountId(), null, Money.format(row.amount())),
                    row.tradeNo(), traceId);
        } catch (BizException e) {
            // 提交未确认不作为失败依据；预留保留，下一轮沿原单继续
            log.warn("取款提交重发未完成，保留预留: tradeNo={}", row.tradeNo(), e);
        }
    }

    /**
     * 编排核心：风控 → 账户侧预留 → 取款提交。
     * 风控拒绝对外抛 422；下游暂不可用时保留原单当前状态并交由恢复扫描继续。
     */
    private void advance(String tradeNo, String identityId, String accountId, BigDecimal amount,
            String traceId) {
        Decision decision;
        try {
            Snapshot snapshot = account.snapshot(
                    new SnapshotRequest(identityId, "WITHDRAWAL", accountId, accountId, null), traceId);
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
            outcome = account.reserve(OutgoingCommands.command(tradeNo, accountId, null, Money.format(amount)),
                    tradeNo, traceId);
        } catch (BizException e) {
            log.warn("账户侧预留未完成，原单保留当前状态: tradeNo={}", tradeNo, e);
            return;
        }
        if ("REJECTED".equals(outcome.status())) {
            // S2 已在本地事务持久化拒绝状态（冻结/可用余额不足/限额复核）；对外映射相应业务错误
            throw new BizException(toErrorCode(outcome.reasonCode()), "交易被拒绝: " + outcome.reasonCode());
        }
        if (!"PROCESSING".equals(outcome.status())) return; // 终态或已未知，交由查询/恢复收敛
        try {
            account.withdrawalCompletion(OutgoingCommands.command(tradeNo, accountId, null, Money.format(amount)),
                    tradeNo, traceId);
        } catch (BizException e) {
            // 提交未确认：不发送释放（超时或未知不得作为释放依据），保留预留由恢复扫描沿原单推进
            log.warn("取款提交未确认，保留预留: tradeNo={}", tradeNo, e);
        }
    }

    private CheckRequest checkRequest(String tradeNo, String identityId, BigDecimal amount,
            AccountSnapshot accountSnapshot) {
        AccountView view = new AccountView(accountSnapshot.accountId(), accountSnapshot.status(),
                accountSnapshot.effectiveSingleLimit(), accountSnapshot.effectiveDailyLimit(),
                accountSnapshot.dailyUsedAmount());
        return new CheckRequest(tradeNo, identityId, "WITHDRAWAL", Money.format(amount), "CNY",
                view, (PayeeView) null);
    }

    private ErrorCode toErrorCode(String reasonCode) {
        try {
            return ErrorCode.valueOf(reasonCode);
        } catch (IllegalArgumentException unknown) {
            return ErrorCode.ACCOUNT_STATUS_INVALID;
        }
    }
}
