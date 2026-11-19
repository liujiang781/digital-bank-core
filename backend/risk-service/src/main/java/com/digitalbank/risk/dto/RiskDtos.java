package com.digitalbank.risk.dto;

/** 风控求值请求与判定；入参账户视图来自 S3 从 S2 获取的授权快照。 */
public final class RiskDtos {
    private RiskDtos() { }

    public record CheckRequest(String tradeNo, String identityId, String businessType,
            String amount, String currency, AccountView account, PayeeView payee) {
        public record AccountView(String accountId, String status, String effectiveSingleLimit,
                String effectiveDailyLimit, String dailyUsedAmount) { }

        public record PayeeView(String accountId, String status) { }
    }

    /** result: PASS/REJECT；reasonCode 使用对外错误码名；eventId 为风险事件标识，REJECT 时必填。 */
    public record Decision(String tradeNo, String result, String reasonCode, String eventId) { }
}
