package com.digitalbank.risk.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.auth.SessionVerifier;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * S3 P2/待评审风控接口对外测试：告警处置、规则提交/审批、名单提交/审批。
 * 身份全部来自服务端会话桩，覆盖角色/账户范围授权、审批职责分离与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RiskP2ApiTest {
    private static final String RISK1_TOKEN = "r".repeat(43);
    private static final String RISK2_TOKEN = "s".repeat(43);
    private static final String ADMIN_TOKEN = "t".repeat(43);
    private static final String OPS_TOKEN = "u".repeat(43);
    private static final String IN_SCOPE = "ACC_P2_IN";
    private static final String OUT_SCOPE = "ACC_P2_OUT";
    private static final String REQ1 = "11111111-1111-4111-8111-111111111111";
    private static final String REQ2 = "22222222-2222-4222-8222-222222222222";
    private static final String REQ3 = "33333333-3333-4333-8333-333333333333";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private SessionVerifier sessionVerifier;

    @BeforeEach
    void reset() {
        when(sessionVerifier.verify(any(), any())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (RISK1_TOKEN.equals(token)) {
                return new SessionIdentity("c-risk1", "identity-risk1", Set.of("RISK"), Set.of(IN_SCOPE));
            }
            if (RISK2_TOKEN.equals(token)) {
                return new SessionIdentity("c-risk2", "identity-risk2", Set.of("RISK"), Set.of(IN_SCOPE));
            }
            if (ADMIN_TOKEN.equals(token)) {
                return new SessionIdentity("c-admin", "identity-admin", Set.of("ADMIN"), Set.of(IN_SCOPE));
            }
            if (OPS_TOKEN.equals(token)) {
                return new SessionIdentity("c-ops", "identity-ops", Set.of("OPERATIONS"), Set.of(IN_SCOPE));
            }
            throw AuthTokens.unauthenticated();
        });
        jdbc.update("DELETE FROM risk_change_approval");
        jdbc.update("DELETE FROM risk_alert_disposal");
        jdbc.update("DELETE FROM risk_change_request");
        jdbc.update("DELETE FROM risk_list_entry");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM risk_event");
        jdbc.update("DELETE FROM risk_rule");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("DELETE FROM account");
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", IN_SCOPE);
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", OUT_SCOPE);
        jdbc.update("INSERT INTO risk_rule (rule_id, rule_type, enabled, rule_version, "
                        + "deposit_single_limit, outgoing_single_limit, outgoing_daily_limit) "
                        + "VALUES ('RK-LIMIT', 'LIMIT_POLICY', 1, 2, 5000.00, 3000.00, 10000.00), "
                        + "('RK-STATUS', 'ACCOUNT_STATUS', 1, 1, NULL, NULL, NULL)");
        jdbc.update("MERGE INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash, "
                        + "account_id, amount, currency, status) KEY (trade_no) "
                        + "VALUES ('TRD-P2', 'owner', 'DEPOSIT', 'req-p2', 'hash-p2', ?, 100.00, 'CNY', 'REJECT')",
                IN_SCOPE);
        jdbc.update("INSERT INTO risk_event (event_id, trade_no, rule_id, rule_version, operator_identity_id, "
                        + "reason_code, result) VALUES ('EVT-P2', 'TRD-P2', 'RK-LIMIT', 2, 'SYSTEM', "
                        + "'SINGLE_LIMIT', 'REJECT')");
    }

    // ---------- 告警处置 ----------

    @Test
    @Transactional
    void onlyScopedRiskCanApproveTheMatchingActiveRiskRestriction() throws Exception {
        jdbc.update("UPDATE account SET status = 'FROZEN' WHERE account_id IN (?, ?)", IN_SCOPE, OUT_SCOPE);
        jdbc.update("INSERT INTO account_status_log (id, account_id, new_status, change_reason) "
                + "VALUES (1001, ?, 'FROZEN', 'RISK: review'), (1002, ?, 'FROZEN', 'RISK: unrelated'), "
                + "(1003, ?, 'FROZEN', 'OPERATIONS: review')", IN_SCOPE, OUT_SCOPE, IN_SCOPE);
        String path = "/api/v1/risk/accounts/{accountId}/restrictions/{restrictionId}/release-approval";
        String basis = "{\"basis\":\"风控复核通过\"}";
        mockMvc.perform(post(path, IN_SCOPE, 1001).header("Authorization", "Bearer " + OPS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(basis))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path, OUT_SCOPE, 1002).header("Authorization", "Bearer " + RISK1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(basis))
                .andExpect(status().isForbidden());
        for (long restrictionId : new long[] {1002, 1003}) {
            mockMvc.perform(post(path, IN_SCOPE, restrictionId).header("Authorization", "Bearer " + RISK1_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(basis))
                    .andExpect(jsonPath("$.code").value("ACCOUNT_STATUS_INVALID"));
        }
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post(path, IN_SCOPE, 1001).header("Authorization", "Bearer " + RISK1_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(basis))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.restrictionId").value(1001));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM risk_freeze_release", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'RISK_FREEZE_RELEASE_APPROVE'", Integer.class));
        assertEquals("FROZEN", jdbc.queryForObject("SELECT status FROM account WHERE account_id = ?", String.class, IN_SCOPE));
    }

    @Test
    void riskWriterCanDisposeAlertWithIdempotency() throws Exception {
        String body = "{\"decision\":\"CONFIRMED\",\"basis\":\"人工确认异常\",\"requestId\":\"" + REQ1 + "\"}";
        mockMvc.perform(post("/api/v1/risk/alerts/EVT-P2/dispose")
                        .header("Authorization", "Bearer " + RISK1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value("EVT-P2"))
                .andExpect(jsonPath("$.data.decision").value("CONFIRMED"));
        // 同键重放返回原结果，不新增处置记录
        mockMvc.perform(post("/api/v1/risk/alerts/EVT-P2/dispose")
                        .header("Authorization", "Bearer " + RISK1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("CONFIRMED"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM risk_alert_disposal", Integer.class));
    }

    @Test
    void alertDisposalRejectsOpsAdminAndBadDecision() throws Exception {
        String body = "{\"decision\":\"CONFIRMED\",\"basis\":\"依据\",\"requestId\":\"" + REQ1 + "\"}";
        for (String token : new String[] {ADMIN_TOKEN, OPS_TOKEN}) {
            mockMvc.perform(post("/api/v1/risk/alerts/EVT-P2/dispose")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mockMvc.perform(post("/api/v1/risk/alerts/EVT-P2/dispose")
                        .header("Authorization", "Bearer " + RISK1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"MAYBE\",\"basis\":\"依据\",\"requestId\":\"" + REQ1 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 规则提交与审批 ----------

    @Test
    void ruleChangeRequiresDifferentApproverAndAppliesOnApprove() throws Exception {
        String submit = "{\"outgoingSingleLimit\":\"2000.00\",\"expectedVersion\":2,"
                + "\"reason\":\"下调单笔限额\",\"requestId\":\"" + REQ1 + "\"}";
        String changeId = mockMvc.perform(put("/api/v1/risk/rules/RK-LIMIT")
                        .header("Authorization", "Bearer " + RISK1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(submit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(changeId, "$.data.changeId");

        // 提交人不能自审
        String approveBody = "{\"decision\":\"APPROVE\",\"note\":\"同意\",\"requestId\":\"" + REQ2 + "\"}";
        mockMvc.perform(post("/api/v1/risk/changes/" + id + "/approve")
                        .header("Authorization", "Bearer " + RISK1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 另一名风控审批通过后规则生效、版本递增
        mockMvc.perform(post("/api/v1/risk/changes/" + id + "/approve")
                        .header("Authorization", "Bearer " + RISK2_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
        assertEquals(3, jdbc.queryForObject(
                "SELECT rule_version FROM risk_rule WHERE rule_id = 'RK-LIMIT'", Integer.class));
        assertEquals(0, new BigDecimal("2000.00").compareTo(jdbc.queryForObject(
                "SELECT outgoing_single_limit FROM risk_rule WHERE rule_id = 'RK-LIMIT'", BigDecimal.class)));
    }

    @Test
    void accountStatusRuleCannotBeDisabled() throws Exception {
        String submit = "{\"enabled\":false,\"reason\":\"关闭状态校验\",\"requestId\":\"" + REQ1 + "\"}";
        String changeId = mockMvc.perform(put("/api/v1/risk/rules/RK-STATUS")
                        .header("Authorization", "Bearer " + RISK1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(submit))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(changeId, "$.data.changeId");
        mockMvc.perform(post("/api/v1/risk/changes/" + id + "/approve")
                        .header("Authorization", "Bearer " + RISK2_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"note\":\"\",\"requestId\":\"" + REQ2 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void ruleReadAllowsOperationsAndRuleWritesForbidAdminOps() throws Exception {
        mockMvc.perform(get("/api/v1/risk/rules").header("Authorization", "Bearer " + OPS_TOKEN))
                .andExpect(status().isOk());
        for (String token : new String[] {ADMIN_TOKEN, OPS_TOKEN}) {
            mockMvc.perform(put("/api/v1/risk/rules/RK-LIMIT")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"x\",\"requestId\":\"" + REQ1 + "\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------- 名单提交与审批 ----------

    @Test
    void listChangeAppliedAfterApprovalAndScopedByAccount() throws Exception {
        String submit = "{\"listType\":\"BLACKLIST\",\"subjectType\":\"ACCOUNT\",\"subjectId\":\"" + IN_SCOPE
                + "\",\"reasonCode\":\"FRAUD\",\"enabled\":true,\"op\":\"UPSERT\","
                + "\"reason\":\"可疑账户\",\"requestId\":\"" + REQ3 + "\"}";
        String response = mockMvc.perform(post("/api/v1/risk/lists/changes")
                        .header("Authorization", "Bearer " + RISK1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(submit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entryId").exists())
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_list_entry", Integer.class));
        String changeId = jdbc.queryForList(
                "SELECT change_id FROM risk_change_request WHERE change_type = 'LIST'", String.class).getFirst();
        mockMvc.perform(post("/api/v1/risk/changes/" + changeId + "/approve")
                        .header("Authorization", "Bearer " + RISK2_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"note\":\"同意\",\"requestId\":\"" + REQ1 + "\"}"))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM risk_list_entry", Integer.class));

        // 查询按账户范围：范围外账户不出现
        jdbc.update("INSERT INTO risk_list_entry (entry_id, list_type, subject_type, subject_id, enabled, "
                        + "created_by) VALUES ('E-OUT', 'BLACKLIST', 'ACCOUNT', ?, 1, 'risk1')", OUT_SCOPE);
        mockMvc.perform(get("/api/v1/risk/lists").header("Authorization", "Bearer " + RISK1_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void listSubmitRejectsOutOfScopeAccount() throws Exception {
        String submit = "{\"listType\":\"BLACKLIST\",\"subjectType\":\"ACCOUNT\",\"subjectId\":\"" + OUT_SCOPE
                + "\",\"reasonCode\":\"FRAUD\",\"op\":\"UPSERT\",\"reason\":\"越权\",\"requestId\":\"" + REQ3 + "\"}";
        mockMvc.perform(post("/api/v1/risk/lists/changes")
                        .header("Authorization", "Bearer " + RISK1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(submit))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
