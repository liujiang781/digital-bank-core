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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 本行转账受理与编排（交接文档第 6 节固定顺序）：
 * 认领原单并写 transfer_order → 风控（出账单笔/日累计）→ S2 reserve → S2 internal-transfers。
 * transfer_order 由 S3 独占写入，S2 按其复核付款方、收款方、金额与币种，受理后不得更换要素。
 * 转账是本地业务，不经模拟渠道；失败只按明确的本地失败依据走 S2 releases，
 * 超时或未知不得作为释放依据。只有首次认领原单的一方启动首次编排；
 * 重复请求只返回当前结果，未完成步骤由恢复扫描沿原 tradeNo 推进。
 */
@Service
public class TransferService {
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final IdempotencyService idempotency;
    private final AccountClient account;
    private final RiskClient risk;
    private final TradeStateReader stateReader;
    private final JdbcTemplate jdbc;

    public TransferService(IdempotencyService idempotency, AccountClient account,
            RiskClient risk, TradeStateReader stateReader, JdbcTemplate jdbc) {
        this.idempotency = idempotency;
        this.account = account;
        this.risk = risk;
        this.stateReader = stateReader;
        this.jdbc = jdbc;
    }

    public TradeView transfer(Actor actor, String accountId, String payeeAccountId, String amountText,
            String currency, String purpose, String requestId, String traceId) {
        if (currency != null && !"CNY".equals(currency)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "币种固定为 CNY");
        }
        if (payeeAccountId == null || payeeAccountId.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "收款账户不能为空");
        }
        if (payeeAccountId.equals(accountId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不能向同一账户转账");
        }
        BigDecimal amount = Money.parsePositive(amountText);
        // 对象权限先于认领：S2 快照复核付款账户归属，并返回收款方状态（不暴露收款方余额）
        account.snapshot(new SnapshotRequest(actor.identityId(), "INTERNAL_TRANSFER", accountId, accountId,
                payeeAccountId), traceId);
        String requestHash = Fingerprints.requestHash("INTERNAL_TRANSFER", accountId, accountId,
                payeeAccountId, Money.format(amount), "CNY", purpose);
        Claim claim = idempotency.claim(actor.identityId(), "INTERNAL_TRANSFER", requestId, requestHash,
                accountId, amount, "CNY");
        if (!claim.isNew()) {
            // 重复请求命中已有原单：只返回当前结果，不再次启动编排
            return stateReader.currentView(claim.tradeNo(), traceId);
        }
        // 认领原单后先落转账单，再进入风控与资金命令（S2 reserve 复核 transfer_order）
        ensureTransferOrder(claim.tradeNo(), accountId, payeeAccountId, amount);
        advance(claim.tradeNo(), actor.identityId(), accountId, payeeAccountId, amount, traceId);
        return stateReader.currentView(claim.tradeNo(), traceId);
    }

    /** 恢复扫描入口：ACCEPTED 原单沿已登记转账单继续编排；转账单缺失时转人工，不凭猜测恢复。 */
    public void resume(TradeRow row, String traceId) {
        try {
            String payeeAccountId = findPayee(row.tradeNo());
            if (payeeAccountId == null) {
                log.warn("转账单缺失，无法沿原单恢复，转人工核对: tradeNo={}", row.tradeNo());
                return;
            }
            advance(row.tradeNo(), row.identityId(), row.accountId(), payeeAccountId, row.amount(), traceId);
        } catch (BizException e) {
            log.warn("转账恢复编排未完成，等待下一轮扫描: tradeNo={}, status={}", row.tradeNo(), row.status(), e);
        }
    }

    /** 恢复扫描入口：PROCESSING 原单先查资金结果，非终态则幂等重发提交命令。 */
    public void resumeProcessing(TradeRow row, String traceId) {
        String payeeAccountId = findPayee(row.tradeNo());
        if (payeeAccountId == null) {
            log.warn("转账单缺失，无法重发提交，转人工核对: tradeNo={}", row.tradeNo());
            return;
        }
        try {
            Outcome outcome = account.outcome(row.tradeNo(), traceId);
            if (DepositService.isTerminal(outcome.status())) return;
        } catch (BizException e) {
            log.warn("查询转账资金结果失败，等待下一轮: tradeNo={}", row.tradeNo(), e);
            return;
        }
        try {
            account.internalTransfer(OutgoingCommands.command(row.tradeNo(), row.accountId(), payeeAccountId,
                    Money.format(row.amount())), row.tradeNo(), traceId);
        } catch (BizException e) {
            // 提交未确认不作为失败依据；预留保留，下一轮沿原单继续
            log.warn("转账提交重发未完成，保留预留: tradeNo={}", row.tradeNo(), e);
        }
    }

    /**
     * 幂等写入转账单（一交易一单）；恢复路径对缺失记录补写。
     * 双方与金额以本次调用为准与原单一致，已有记录不覆盖既有要素（唯一键 trade_no 兜底）。
     */
    public void ensureTransferOrder(String tradeNo, String payerAccountId, String payeeAccountId,
            BigDecimal amount) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, tradeNo);
        if (existing != null && existing > 0) return;
        try {
            jdbc.update("INSERT INTO transfer_order (transfer_id, trade_no, transfer_type, payer_account_id, "
                            + "payee_account_id, amount, currency, clearing_status) "
                            + "VALUES (?, ?, 'INTERNAL', ?, ?, ?, 'CNY', 'NOT_APPLICABLE')",
                    newTransferId(), tradeNo, payerAccountId, payeeAccountId, amount);
        } catch (DuplicateKeyException duplicate) {
            // 并发恢复已写入同一 tradeNo：唯一键兜底，保留先到要素
        }
    }

    private String findPayee(String tradeNo) {
        List<String> rows = jdbc.queryForList(
                "SELECT payee_account_id FROM transfer_order WHERE trade_no = ?", String.class, tradeNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String newTransferId() {
        return "TFR" + LocalDateTime.now(ZONE).format(DAY)
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * 编排核心：风控（含收款方状态）→ 账户侧预留 → 转账提交。
     * 风控拒绝对外抛 422；下游暂不可用时保留原单当前状态并交由恢复扫描继续。
     */
    private void advance(String tradeNo, String identityId, String accountId, String payeeAccountId,
            BigDecimal amount, String traceId) {
        Decision decision;
        try {
            Snapshot snapshot = account.snapshot(new SnapshotRequest(identityId, "INTERNAL_TRANSFER",
                    accountId, accountId, payeeAccountId), traceId);
            decision = risk.check(checkRequest(tradeNo, identityId, amount, snapshot), traceId);
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
            outcome = account.reserve(OutgoingCommands.command(tradeNo, accountId, payeeAccountId,
                    Money.format(amount)), tradeNo, traceId);
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
            account.internalTransfer(OutgoingCommands.command(tradeNo, accountId, payeeAccountId,
                    Money.format(amount)), tradeNo, traceId);
        } catch (BizException e) {
            // 提交未确认：不发送释放（超时或未知不得作为释放依据），保留预留由恢复扫描沿原单推进
            log.warn("转账提交未确认，保留预留: tradeNo={}", tradeNo, e);
        }
    }

    private CheckRequest checkRequest(String tradeNo, String identityId, BigDecimal amount, Snapshot snapshot) {
        AccountSnapshot accountSnapshot = snapshot.account();
        AccountView view = new AccountView(accountSnapshot.accountId(), accountSnapshot.status(),
                accountSnapshot.effectiveSingleLimit(), accountSnapshot.effectiveDailyLimit(),
                accountSnapshot.dailyUsedAmount());
        PayeeView payee = snapshot.payee() == null ? null
                : new PayeeView(snapshot.payee().accountId(), snapshot.payee().status());
        return new CheckRequest(tradeNo, identityId, "INTERNAL_TRANSFER", Money.format(amount), "CNY", view, payee);
    }

    private ErrorCode toErrorCode(String reasonCode) {
        try {
            return ErrorCode.valueOf(reasonCode);
        } catch (IllegalArgumentException unknown) {
            return ErrorCode.ACCOUNT_STATUS_INVALID;
        }
    }
}
