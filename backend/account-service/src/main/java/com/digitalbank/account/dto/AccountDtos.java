package com.digitalbank.account.dto;

import com.digitalbank.account.support.AmountStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.OffsetDateTime;
import java.util.List;

public final class AccountDtos {
    private AccountDtos() { }

    public record OpenRequest(String currency, String requestId) { }
    public record FreezeRequest(String reason, String requestId) { }
    public record UnfreezeRequest(String reason, String releaseEvidence,
            List<Long> resolvedRestrictionIds, String requestId) { }
    public record CloseRequest(String requestId) { }
    public record LimitsRequest(
            @JsonDeserialize(using = AmountStringDeserializer.class) String personalSingleLimit,
            @JsonDeserialize(using = AmountStringDeserializer.class) String personalDailyLimit,
            String requestId) { }

    public record AccountView(String accountId, String currency, String status,
            String bookBalance, String reservedAmount, String availableBalance,
            String personalSingleLimit, String personalDailyLimit, OffsetDateTime createdAt,
            List<RestrictionView> restrictions) { }
    public record BalanceView(String accountId, String currency, String status,
            String bookBalance, String reservedAmount, String availableBalance) { }
    public record StatusResult(String accountId, String status) { }
    public record LimitsResult(String accountId, String personalSingleLimit,
            String personalDailyLimit) { }
    public record RestrictionView(long restrictionId, String reason) { }
    public record FreezeResult(String accountId, String status, List<RestrictionView> restrictions) { }
    public record OperationView(long operationId, String action, String reason,
            String result, OffsetDateTime occurredAt) { }
    public record FundEntryView(String entryId, String tradeNo, String entryRole,
            String direction, String amount, String bookBalanceAfter, OffsetDateTime occurredAt) { }
    public record Page<T>(List<T> items, long total, int page, int pageSize) { }
}
