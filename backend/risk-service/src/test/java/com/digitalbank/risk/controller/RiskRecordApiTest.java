package com.digitalbank.risk.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.auth.SessionVerifier;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S3 风险记录查询对外接口测试：真实 Spring MVC 与认证过滤器，身份/角色/账户范围全部来自服务端会话；
 * 覆盖账户范围过滤、字段关联、条件过滤、审计与越权拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RiskRecordApiTest {
    private static final String OPS_TOKEN = "a".repeat(43);
    private static final String RISK_TOKEN = "b".repeat(43);
    private static final String ADMIN_TOKEN = "c".repeat(43);
    private static final String NO_SCOPE_TOKEN = "d".repeat(43);
    private static final String CUSTOMER_TOKEN = "e".repeat(43);
    private static final String IN_SCOPE = "ACC_RK_IN";
    private static final String OUT_SCOPE = "ACC_RK_OUT";

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
            if (OPS_TOKEN.equals(token)) {
                return new SessionIdentity("customer-ops", "identity-ops",
                        Set.of("OPERATIONS"), Set.of(IN_SCOPE));
            }
            if (RISK_TOKEN.equals(token)) {
                return new SessionIdentity("customer-risk", "identity-risk",
                        Set.of("RISK"), Set.of(IN_SCOPE));
            }
            if (ADMIN_TOKEN.equals(token)) {
                return new SessionIdentity("customer-admin", "identity-admin",
                        Set.of("ADMIN"), Set.of(IN_SCOPE));
            }
            if (NO_SCOPE_TOKEN.equals(token)) {
                return new SessionIdentity("customer-no-scope", "identity-no-scope", Set.of("RISK"), Set.of());
            }
            if (CUSTOMER_TOKEN.equals(token)) {
                return new SessionIdentity("customer-normal", "identity-normal");
            }
            throw AuthTokens.unauthenticated();
        });
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM risk_event");
        jdbc.update("DELETE FROM risk_rule");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("DELETE FROM account");
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", IN_SCOPE);
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", OUT_SCOPE);
        jdbc.update("INSERT INTO risk_rule (rule_id, rule_type, enabled, rule_version) VALUES "
                + "('RK-S3-1', 'SINGLE_LIMIT', 1, 3), ('RK-S3-2', 'ACCOUNT_STATUS', 1, 2)");
        seedTrade("TRD-E1", IN_SCOPE, "DEPOSIT", "100.00", "SUCCESS");
        seedTrade("TRD-E2", IN_SCOPE, "WITHDRAWAL", "200.00", "FAILED");
        seedTrade("TRD-E3", OUT_SCOPE, "DEPOSIT", "300.00", "SUCCESS");
        seedEvent("EVT-S3-1", "TRD-E1", "RK-S3-1", 3, "SINGLE_LIMIT", "REJECT");
        seedEvent("EVT-S3-2", "TRD-E2", "RK-S3-2", 2, "ACCOUNT_STATUS", "REJECT");
        seedEvent("EVT-S3-3", "TRD-E3", "RK-S3-1", 3, "SINGLE_LIMIT", "REJECT");
    }

    private void seedTrade(String tradeNo, String accountId, String businessType, String amount, String status) {
        jdbc.update("MERGE INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash, "
                        + "account_id, amount, currency, status) KEY (trade_no) "
                        + "VALUES (?, 'identity-owner', ?, ?, ?, ?, ?, 'CNY', ?)",
                tradeNo, businessType, "req-" + tradeNo, "hash-" + tradeNo, accountId, amount, status);
    }

    private void seedEvent(String eventId, String tradeNo, String ruleId, int version,
            String reasonCode, String result) {
        jdbc.update("INSERT INTO risk_event (event_id, trade_no, rule_id, rule_version, operator_identity_id, "
                        + "reason_code, result) VALUES (?, ?, ?, ?, 'identity-risk', ?, ?)",
                eventId, tradeNo, ruleId, version, reasonCode, result);
    }

    @Test
    void riskAndOperationsReadersSeeOnlyTheirAccountScopeWithJoinedTradeFields() throws Exception {
        mockMvc.perform(get("/api/v1/risk/events")
                        .param("reason", "实验四风险记录核查")
                        .header("Authorization", "Bearer " + RISK_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data.items[?(@.eventId=='EVT-S3-3')]", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.data.items[?(@.eventId=='EVT-S3-1')].accountId")
                        .value(org.hamcrest.Matchers.hasItem(IN_SCOPE)))
                .andExpect(jsonPath("$.data.items[?(@.eventId=='EVT-S3-1')].businessType")
                        .value(org.hamcrest.Matchers.hasItem("DEPOSIT")))
                .andExpect(jsonPath("$.data.items[?(@.eventId=='EVT-S3-1')].tradeAmount")
                        .value(org.hamcrest.Matchers.hasItem("100.00")))
                .andExpect(jsonPath("$.data.items[?(@.eventId=='EVT-S3-2')].tradeStatus")
                        .value(org.hamcrest.Matchers.hasItem("FAILED")))
                .andExpect(jsonPath("$.data.items[?(@.eventId=='EVT-S3-1')].ruleVersion")
                        .value(org.hamcrest.Matchers.hasItem(3)));

        assertEquals("RISK_RECORD_LIST", jdbc.queryForObject(
                "SELECT action FROM audit_log", String.class));
    }

    @Test
    void operationsReaderUsesTheSameScopeAndQueryIsAudited() throws Exception {
        mockMvc.perform(get("/api/v1/risk/events")
                        .param("reason", "运营核查")
                        .header("Authorization", "Bearer " + OPS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'RISK_RECORD_LIST'",
                Integer.class));
        assertEquals("运营核查", jdbc.queryForObject("SELECT reason FROM audit_log", String.class));
    }

    @Test
    void adminCustomerAndEmptyScopeAreForbidden() throws Exception {
        for (String token : new String[] {ADMIN_TOKEN, NO_SCOPE_TOKEN, CUSTOMER_TOKEN}) {
            mockMvc.perform(get("/api/v1/risk/events")
                            .param("reason", "越权测试")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class));
    }

    @Test
    void filtersCombineTradeRuleReasonResultAndScope() throws Exception {
        mockMvc.perform(get("/api/v1/risk/events")
                        .param("reason", "按条件筛查")
                        .param("result", "REJECT")
                        .param("reasonCode", "ACCOUNT_STATUS")
                        .header("Authorization", "Bearer " + OPS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].eventId").value("EVT-S3-2"));

        mockMvc.perform(get("/api/v1/risk/events")
                        .param("reason", "按交易号筛查")
                        .param("tradeNo", "TRD-E3")
                        .header("Authorization", "Bearer " + OPS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void missingReasonIllegalEnumAndBadPagingReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/risk/events").header("Authorization", "Bearer " + OPS_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/risk/events")
                        .param("reason", "非法枚举")
                        .param("result", "MAYBE")
                        .header("Authorization", "Bearer " + OPS_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/risk/events")
                        .param("reason", "非法原因码")
                        .param("reasonCode", "BOGUS")
                        .header("Authorization", "Bearer " + OPS_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/risk/events")
                        .param("reason", "非法分页")
                        .param("pageSize", "0")
                        .header("Authorization", "Bearer " + OPS_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class));
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/risk/events").param("reason", "未登录"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
