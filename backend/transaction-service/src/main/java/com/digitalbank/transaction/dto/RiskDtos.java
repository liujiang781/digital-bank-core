package com.digitalbank.transaction.dto;

/** 风控求值请求与判定（交接文档：POST /internal/v1/risk/checks，短路顺序：状态→单笔→日累计）。 */
public final class RiskDtos {
    private RiskDtos() { }

    public record CheckRequest(String tradeNo, String identityId, String businessType,
            String amount, String currency, AccountView account, PayeeView payee) { }

    public record AccountView(String accountId, String status, String effectiveSingleLimit,
            String effectiveDailyLimit, String dailyUsedAmount) { }

    public record PayeeView(String accountId, String status) { }

    /** result: PASS/REJECT；reasonCode 使用对外错误码名；eventId 为风险事件标识，REJECT 时必填。 */
    public record Decision(String tradeNo, String result, String reasonCode, String eventId) { }
}
