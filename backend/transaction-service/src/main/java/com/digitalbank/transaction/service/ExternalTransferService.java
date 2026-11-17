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
import com.digitalbank.transaction.dto.TransactionDtos.ExternalTransferResult;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 跨行转账受理与编排（S2 与 S3 跨行转账交接文档固定顺序）：
 * 认领原单并写 transfer_order（EXTERNAL、SUBMITTED）→ 风控（出账单笔/日累计）→ S2 reserve
 * → 模拟渠道 → S2 external-transfer-results。
 * 跨行失败与释放一律由 S2 按渠道回执推进：明确失败上报 FAILED 回执，不得使用 releases；
 * 超时或未知上报 UNKNOWN 保留待确认。回执重放幂等，RECOVERING 等非终态沿原凭证重发直至收敛。
 * 跨行收款方以脱敏引用（externalBankCode + externalPayeeRef）进指纹与转账单，受理后不得更换要素。
 */
@Service
public class ExternalTransferService {
    private static final Logger log = LoggerFactory.getLogger(ExternalTransferService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final IdempotencyService idempotency;
    private final AccountClient account;
    private final RiskClient risk;
    private final TradeStateReader stateReader;
    private final MockChannelService channel;
    private final JdbcTemplate jdbc;

    public ExternalTransferService(IdempotencyService idempotency, AccountClient account,
            RiskClient risk, TradeStateReader stateReader, MockChannelService channel, JdbcTemplate jdbc) {
        this.idempotency = idempotency;
        this.account = account;
        this.risk = risk;
        this.stateReader = stateReader;
        this.channel = channel;
        this.jdbc = jdbc;
    }

    public TradeView transfer(Actor actor, String payerAccountId, String externalBankCode,
            String externalPayeeRef, String amountText, String currency, String purpose,
            String requestId, String traceId) {
        if (currency != null && !"CNY".equals(currency)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "币种固定为 CNY");
        }
        if (externalBankCode == null || externalBankCode.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "外部银行代码不能为空");
        }
        if (externalPayeeRef == null || externalPayeeRef.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "外部收款方引用不能为空");
        }
        BigDecimal amount = Money.parsePositive(amountText);
        // 对象权限先于认领：S2 快照复核付款账户归属与状态上下文（跨行无本行收款账户）
        account.snapshot(new SnapshotRequest(actor.identityId(), "EXTERNAL_TRANSFER", payerAccountId,
                payerAccountId, null), traceId);
        // 指纹纳入付款账户、外部银行、外部收款方引用、金额与币种（交接文档第 1 节）
        String requestHash = Fingerprints.requestHash("EXTERNAL_TRANSFER", payerAccountId, payerAccountId,
                externalBankCode + ":" + externalPayeeRef, Money.format(amount), "CNY", purpose);
        Claim claim = idempotency.claim(actor.identityId(), "EXTERNAL_TRANSFER", requestId, requestHash,
                payerAccountId, amount, "CNY");
        if (!claim.isNew()) {
            // 重复请求命中已有原单：只返回当前结果，不再次启动编排
            return stateReader.currentView(claim.tradeNo(), traceId);
        }
        ensureTransferOrder(claim.tradeNo(), payerAccountId, externalBankCode, externalPayeeRef, amount);
        advance(claim.tradeNo(), actor.identityId(), payerAccountId, amount, traceId);
        return stateReader.currentView(claim.tradeNo(), traceId);
    }

    /** 恢复扫描入口：ACCEPTED 原单沿已登记转账单继续编排；转账单缺失转人工，不凭猜测恢复。 */
    public void resume(TradeRow row, String traceId) {
        try {
            if (!orderExists(row.tradeNo())) {
                log.warn("跨行转账单缺失，无法沿原单恢复，转人工核对: tradeNo={}", row.tradeNo());
                return;
            }
            advance(row.tradeNo(), row.identityId(), row.accountId(), row.amount(), traceId);
        } catch (BizException e) {
            log.warn("跨行恢复编排未完成，等待下一轮扫描: tradeNo={}, status={}", row.tradeNo(), row.status(), e);
        }
    }

    /** 恢复与主动查询共用：沿唯一渠道记录重放回执，S2 幂等推进扣款或释放直至终态。 */
    public void reportExternalResult(String tradeNo, String payerAccountId, BigDecimal amount,
            ChannelRow record, String traceId) {
        try {
            account.externalTransferResult(new ExternalTransferResult(tradeNo, payerAccountId,
                    Money.format(amount), "CNY", record.result(), record.channelRequestId(),
                    record.evidenceId(), OffsetDateTime.now(ZONE), record.reasonCode()), tradeNo, traceId);
        } catch (BizException e) {
            log.warn("跨行回执上报未完成，恢复任务会沿原单重试: tradeNo={}, result={}", tradeNo, record.result(), e);
        }
    }

    /**
     * 幂等写入跨行转账单（一交易一单）：transfer_type=EXTERNAL、payee_account_id 为空、
     * 清算标记初始 SUBMITTED（是否已调用渠道以 S3 渠道记录为准）。已有记录不覆盖既有要素。
     */
    public void ensureTransferOrder(String tradeNo, String payerAccountId, String externalBankCode,
            String externalPayeeRef, BigDecimal amount) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, tradeNo);
        if (existing != null && existing > 0) return;
        try {
            jdbc.update("INSERT INTO transfer_order (transfer_id, trade_no, transfer_type, payer_account_id, "
                            + "payee_account_id, external_bank_code, external_payee_ref, amount, currency, "
                            + "clearing_status) VALUES (?, ?, 'EXTERNAL', ?, NULL, ?, ?, ?, 'CNY', 'SUBMITTED')",
                    newTransferId(), tradeNo, payerAccountId, externalBankCode, externalPayeeRef, amount);
        } catch (DuplicateKeyException duplicate) {
            // 并发恢复已写入同一 tradeNo：唯一键兜底，保留先到要素
        }
    }

    private boolean orderExists(String tradeNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, tradeNo);
        return count != null && count > 0;
    }

    private String newTransferId() {
        return "TFR" + LocalDateTime.now(ZONE).format(DAY)
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * 编排核心：风控 → 账户侧预留 → 渠道 → 回执上报。
     * 风控拒绝对外抛 422；下游暂不可用时保留原单当前状态并交由恢复扫描继续。
     */
    private void advance(String tradeNo, String identityId, String payerAccountId, BigDecimal amount,
            String traceId) {
        Decision decision;
        try {
            Snapshot snapshot = account.snapshot(new SnapshotRequest(identityId, "EXTERNAL_TRANSFER",
                    payerAccountId, payerAccountId, null), traceId);
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
            outcome = account.reserve(OutgoingCommands.command(tradeNo, payerAccountId, null,
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
        // 预留成功后才发起渠道；channelRequestId 固定 CHD+tradeNo，重试复用唯一渠道记录
        ChannelRow record = channel.createOrGet(tradeNo, payerAccountId, amount, "CNY");
        reportExternalResult(tradeNo, payerAccountId, amount, record, traceId);
    }

    private CheckRequest checkRequest(String tradeNo, String identityId, BigDecimal amount,
            AccountSnapshot accountSnapshot) {
        AccountView view = new AccountView(accountSnapshot.accountId(), accountSnapshot.status(),
                accountSnapshot.effectiveSingleLimit(), accountSnapshot.effectiveDailyLimit(),
                accountSnapshot.dailyUsedAmount());
        return new CheckRequest(tradeNo, identityId, "EXTERNAL_TRANSFER", Money.format(amount), "CNY",
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
