package com.digitalbank.transaction.dto;

import com.digitalbank.transaction.support.AmountStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.OffsetDateTime;
import java.util.List;

public final class TransactionDtos {
    private TransactionDtos() { }

    // ---- 对外接口 ----

    public record DepositRequest(String accountId,
            @JsonDeserialize(using = AmountStringDeserializer.class) String amount,
            String currency, String purpose, String requestId) { }

    public record WithdrawalRequest(String accountId,
            @JsonDeserialize(using = AmountStringDeserializer.class) String amount,
            String currency, String purpose, String requestId) { }

    /**
     * 转账受理（跨行交接文档）：付款账户优先 payerAccountId，兼容旧字段 accountId（两者同传且不同值报 400）。
     * 本行分支填 payeeAccountId；跨行分支填 externalBankCode + externalPayeeRef，payeeAccountId 留空。
     */
    public record TransferRequest(String accountId, String payerAccountId, String payeeAccountId,
            String externalBankCode, String externalPayeeRef,
            @JsonDeserialize(using = AmountStringDeserializer.class) String amount,
            String currency, String purpose, String requestId) { }

    public record TradeView(String tradeNo, String status, String reasonCode) { }

    public record TradeDetailView(String tradeNo, String businessType, String status,
            String amount, String failureReason, String accountId) { }

    public record TradeSummary(String tradeNo, String businessType, String status,
            String amount, String createdAt) { }

    public record TradePage(long total, int page, int pageSize, List<TradeSummary> items) { }

    /** 模拟回单（UC-TR-07）：仅成功交易生成，回单号由交易号确定性派生，重复下载内容一致。 */
    public record ReceiptView(String receiptNo, String tradeNo, String businessType, String status,
            String amount, String currency, String accountId, String channelEvidenceId,
            String completedAt, String issuedAt, String notice) { }

    // ---- 与 S2 交接文档同形的内部契约 DTO ----

    public record Command(String tradeNo, String payerAccountId, String payeeAccountId,
            String accountId, String amount, String currency) { }

    public record DepositResult(String tradeNo, String accountId, String amount, String currency,
            String result, String channelRequestId, String evidenceId,
            OffsetDateTime observedAt, String reasonCode) { }

    public record Failure(String tradeNo, String decision, String reasonCode,
            String evidenceType, String evidenceId) { }

    public record Release(String tradeNo, String payerAccountId, String amount, String currency,
            String reasonCode, String evidenceType, String evidenceId) { }

    /** 跨行渠道回执（S2 external-transfer-results）：与存款 DepositResult 同构，付款账户字段为 payerAccountId。 */
    public record ExternalTransferResult(String tradeNo, String payerAccountId,
            @JsonDeserialize(using = AmountStringDeserializer.class) String amount, String currency,
            String result, String channelRequestId, String evidenceId,
            OffsetDateTime observedAt, String reasonCode) { }

    public record WithdrawalResult(String tradeNo, String payerAccountId, String amount, String currency,
            String result, String channelRequestId, String evidenceId, OffsetDateTime observedAt, String reasonCode) { }

    public record Outcome(String tradeNo, String status, String reservationStatus, String reasonCode) { }

    public record SnapshotRequest(String identityId, String businessType,
            String accountId, String payerAccountId, String payeeAccountId) { }

    public record Snapshot(AccountSnapshot account, PayeeSnapshot payee) { }

    public record AccountSnapshot(String accountId, String currency, String status,
            String bookBalance, String reservedAmount, String availableBalance,
            String effectiveSingleLimit, String effectiveDailyLimit,
            String dailyUsedAmount, int ruleVersion) { }

    public record PayeeSnapshot(String accountId, String currency, String status) { }

    // ---- 风控与模拟渠道 ----

    public record ChannelMode(String result) { }

    public record ChannelRecordView(String tradeNo, String channelRequestId, String accountId,
            String amount, String currency, String result, String evidenceId, String reasonCode,
            String observedAt, int reconcileAttempts, String nextCheckAt, boolean manualReviewRequired) { }

    // ---- 人工核对 / 恢复重试 ----

    /** 运营填写的操作原因；写操作同时携带 UUID requestId 保证幂等。 */
    public record ReasonRequest(String reason, String requestId) { }

    /** 人工核对 / 恢复重试的幂等结果。 */
    public record ManualActionResult(String tradeNo, String action, String status, String channelResult,
            String conclusion, String requestId, String traceId) { }
}
