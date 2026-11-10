package com.digitalbank.account.dto;

import com.digitalbank.account.support.AmountStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.OffsetDateTime;

public final class PostingDtos {
    private PostingDtos() { }

    public record Command(String tradeNo, String payerAccountId, String payeeAccountId,
            String accountId, @JsonDeserialize(using = AmountStringDeserializer.class) String amount,
            String currency) { }

    public record DepositResult(String tradeNo, String accountId,
            @JsonDeserialize(using = AmountStringDeserializer.class) String amount, String currency,
            String result, String channelRequestId, String evidenceId,
            OffsetDateTime observedAt, String reasonCode) { }

    public record Failure(String tradeNo, String decision, String reasonCode,
            String evidenceType, String evidenceId) { }

    public record Release(String tradeNo, String payerAccountId,
            @JsonDeserialize(using = AmountStringDeserializer.class) String amount, String currency,
            String reasonCode, String evidenceType, String evidenceId) { }

    public record Outcome(String tradeNo, String status, String reservationStatus, String reasonCode) { }

    public record SnapshotRequest(String identityId, String businessType,
            String accountId, String payerAccountId, String payeeAccountId) { }

    public record Snapshot(AccountSnapshot account, PayeeSnapshot payee) { }

    public record AccountSnapshot(String accountId, String currency, String status,
            String bookBalance, String reservedAmount, String availableBalance,
            String effectiveSingleLimit, String effectiveDailyLimit,
            String dailyUsedAmount, int ruleVersion) { }

    public record PayeeSnapshot(String accountId, String currency, String status) { }
}
