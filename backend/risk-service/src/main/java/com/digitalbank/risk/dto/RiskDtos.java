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

    // ---- 风险记录查询（UC-RK-04）----

    /** 风险记录：规则命中、拒绝原因与关联交易摘要。 */
    public record RiskRecordView(String eventId, String tradeNo, String ruleId, int ruleVersion,
            String reasonCode, String result, String operatorIdentityId, String createdAt,
            String businessType, String accountId, String tradeAmount, String tradeStatus) { }

    public record RiskRecordPage(long total, int page, int pageSize, java.util.List<RiskRecordView> items) { }

    // ---- 风险告警处置（UC-RK-03）----

    /** decision: CONFIRMED(确认风险)/FALSE_POSITIVE(误报)/ESCALATED(升级)；basis 为处置依据。 */
    public record AlertDisposeRequest(String decision, String basis, String requestId) { }

    public record AlertDisposalView(String eventId, String tradeNo, String decision, String basis,
            String operatorIdentityId, String requestId, String disposedAt, String traceId) { }

    // ---- 风控规则维护（UC-RK-01，提交审批）----

    /** 规则拟变更值；null 字段表示不修改。laidVersion 尽力而为：带 ruleVersion 做乐观校验。 */
    public record RuleChangeRequest(String depositSingleLimit, String outgoingSingleLimit,
            String outgoingDailyLimit, Boolean enabled, Integer expectedVersion,
            String reason, String requestId) { }

    public record RuleView(String ruleId, String ruleType, String depositSingleLimit,
            String outgoingSingleLimit, String outgoingDailyLimit, boolean enabled,
            int ruleVersion, String updatedAt) { }

    public record RuleChangeView(String changeId, String ruleId, String ruleType, String changeJson,
            String status, String submittedBy, String reason, String requestId,
            String approvedBy, String approvalNote, String createdAt, String decidedAt) { }

    public record ChangePage(long total, int page, int pageSize, java.util.List<RuleChangeView> items) { }

    // ---- 规则/名单审批（待评审项，按审批留痕需要自建）----

    /** decision: APPROVE/REJECT；审批不能由提交人本人执行。 */
    public record ApprovalRequest(String decision, String note, String requestId) { }

    // ---- 风险名单维护（UC-RK-02，待评审项）----

    /** listType: BLACKLIST/WHITELIST；subjectType: ACCOUNT/CUSTOMER；op: UPSERT/REMOVE。 */
    public record ListChangeRequest(String listType, String subjectType, String subjectId,
            String reasonCode, Boolean enabled, String op, String reason, String requestId) { }

    public record ListEntryView(String entryId, String listType, String subjectType, String subjectId,
            String reasonCode, boolean enabled, int version, String submittedBy,
            String approvedBy, String createdAt, String updatedAt) { }

    public record ListPage(long total, int page, int pageSize, java.util.List<ListEntryView> items) { }
}
