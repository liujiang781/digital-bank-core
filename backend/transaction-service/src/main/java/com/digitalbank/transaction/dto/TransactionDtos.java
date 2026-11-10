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

    public record TradeView(String tradeNo, String status, String reasonCode) { }

    public record TradeDetailView(String tradeNo, String businessType, String status,
            String amount, String failureReason) { }

    public record TradeSummary(String tradeNo, String businessType, String status,
            String amount, String createdAt) { }

    public record TradePage(long total, int page, int pageSize, List<TradeSummary> items) { }

    // ---- 与 S2 交接文档同形的内部契约 DTO ----

    public record Command(String tradeNo, String payerAccountId, String payeeAccountId,
            String accountId, String amount, String currency) { }

    public record DepositResult(String tradeNo, String accountId, String amount, String currency,
            String result, String channelRequestId, String evidenceId,
            OffsetDateTime observedAt, String reasonCode) { }

    public record Failure(String tradeNo, String decision, String reasonCode,
            String evidenceType, String evidenceId) { }

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
}
