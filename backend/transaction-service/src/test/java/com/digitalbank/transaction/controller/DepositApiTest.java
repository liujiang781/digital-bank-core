package com.digitalbank.transaction.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalbank.transaction.channel.MockChannelService;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.client.RiskClient;
import com.digitalbank.transaction.dto.RiskDtos.CheckRequest;
import com.digitalbank.transaction.dto.RiskDtos.Decision;
import com.digitalbank.transaction.dto.TransactionDtos.AccountSnapshot;
import com.digitalbank.transaction.dto.TransactionDtos.Command;
import com.digitalbank.transaction.dto.TransactionDtos.DepositResult;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.Snapshot;
import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.auth.SessionVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 对外存款接口功能测试：真实 Spring MVC、H2 原单/渠道表，S2 账户接口与风控接口使用桩。
 * 覆盖等价类中的金额、币种、请求号、身份、渠道三态与重放/冲突，并验证对外错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DepositApiTest {
    private static final String IDENTITY = "identity-s3-api";
    private static final String ACCOUNT = "ACC_S3_API";
    private static final String REQUEST = "12345678-1234-4234-8234-123456789abc";
    private static final String TOKEN = "a".repeat(43);
    private static final String OTHER_TOKEN = "b".repeat(43);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockChannelService channel;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountClient account;
    @MockitoBean
    private RiskClient risk;

    @MockitoBean
    private SessionVerifier sessionVerifier;

    @BeforeEach
    void resetData() {
        // 仅替换远程 S1 调用，保留真实认证过滤器，不能靠手工 Principal 绕过 Bearer 校验。
        when(sessionVerifier.verify(any(), any())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (TOKEN.equals(token)) return new SessionIdentity("customer-s3", IDENTITY);
            if (OTHER_TOKEN.equals(token)) return new SessionIdentity("customer-other", "identity-other");
            throw AuthTokens.unauthenticated();
        });
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM mock_channel_deposit");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("DELETE FROM account");
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", ACCOUNT);
        channel.setMode(MockChannelService.SUCCESS);
        stubActiveSnapshot();
        stubRiskPass();
    }

    private void stubActiveSnapshot() {
        Snapshot snapshot = new Snapshot(new AccountSnapshot(ACCOUNT, "CNY", "ACTIVE",
                "50000.00", "0.00", "50000.00", "50000.00", "50000.00", "0.00", 1), null);
        when(account.snapshot(any(), any())).thenReturn(snapshot);
    }

    private void stubRiskPass() {
        when(risk.check(any(), any())).thenAnswer(invocation -> {
            CheckRequest request = invocation.getArgument(0);
            return new Decision(request.tradeNo(), "PASS", null, null);
        });
    }

    private void stubProcessingThen(String finalStatus) {
        when(account.acceptDeposit(any(), any(), any())).thenAnswer(invocation -> {
            Command command = invocation.getArgument(0);
            jdbc.update("UPDATE `transaction` SET status = 'PROCESSING' WHERE trade_no = ?", command.tradeNo());
            return new Outcome(command.tradeNo(), "PROCESSING", "NONE", null);
        });
        when(account.depositResult(any(), any(), any())).thenAnswer(invocation -> {
            DepositResult receipt = invocation.getArgument(0);
            jdbc.update("UPDATE `transaction` SET status = ? WHERE trade_no = ?", finalStatus, receipt.tradeNo());
            return new Outcome(receipt.tradeNo(), finalStatus, "NONE", receipt.reasonCode());
        });
        when(account.outcome(any(), any())).thenAnswer(invocation ->
                new Outcome(invocation.getArgument(0), finalStatus, "NONE", null));
    }

    private String body(String amount, String currency, String requestId) {
        return """
                {"accountId":"%s","amount":"%s","currency":"%s","purpose":"实验四接口测试","requestId":"%s"}
                """.formatted(ACCOUNT, amount, currency, requestId);
    }

    private MvcResult deposit(String amount, String currency, String requestId) throws Exception {
        return mockMvc.perform(post("/api/v1/deposits")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(amount, currency, requestId)))
                .andReturn();
    }

    private String tradeNo(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("tradeNo").asText();
    }

    @Test
    void successfulDepositReturnsSuccessAndDetailIsReadableByOwner() throws Exception {
        stubProcessingThen("SUCCESS");

        MvcResult result = deposit("100.00", "CNY", REQUEST);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("SUCCESS", objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("status").asText());

        mockMvc.perform(get("/api/v1/transactions/{tradeNo}", tradeNo(result))
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.businessType").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.amount").value("100.00"));
    }

    @Test
    void replaySameRequestReturnsOriginalTradeNo() throws Exception {
        stubProcessingThen("SUCCESS");

        MvcResult first = deposit("100.00", "CNY", REQUEST);
        MvcResult replay = deposit("100.00", "CNY", REQUEST);

        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(tradeNo(first), tradeNo(replay));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM `transaction`", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mock_channel_deposit", Integer.class));
    }

    @Test
    void sameRequestDifferentAmountReturns409() throws Exception {
        stubProcessingThen("SUCCESS");

        assertEquals(200, deposit("100.00", "CNY", REQUEST).getResponse().getStatus());
        mockMvc.perform(post("/api/v1/deposits")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200.00", "CNY", REQUEST)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CONFLICT"));
    }

    @Test
    void invalidAmountReturns400BeforeAnyClaim() throws Exception {
        mockMvc.perform(post("/api/v1/deposits")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("0", "CNY", REQUEST)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `transaction`", Integer.class));
    }

    @Test
    void missingRequestIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/deposits")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","amount":"100.00","currency":"CNY"}
                                """.formatted(ACCOUNT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void nonCnyReturns400BeforeAnyClaim() throws Exception {
        mockMvc.perform(post("/api/v1/deposits")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("100.00", "USD", REQUEST)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `transaction`", Integer.class));
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("100.00", "CNY", REQUEST)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void riskRejectReturns422AndCreatesNoChannelRecord() throws Exception {
        // 覆盖 @BeforeEach 中的 PASS 桩，必须用 doAnswer，避免 when(...) 触发旧桩并 NPE
        doAnswer(invocation -> {
            CheckRequest request = invocation.getArgument(0);
            return new Decision(request.tradeNo(), "REJECT", "SINGLE_LIMIT_EXCEEDED", "RSKEVT-API-0001");
        }).when(risk).check(any(), any());
        when(account.recordFailure(any(), any(), any())).thenAnswer(invocation -> {
            com.digitalbank.transaction.dto.TransactionDtos.Failure failure = invocation.getArgument(0);
            return new Outcome(failure.tradeNo(), "REJECTED", "NONE", "SINGLE_LIMIT_EXCEEDED");
        });

        mockMvc.perform(post("/api/v1/deposits")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("50000.01", "CNY", REQUEST)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SINGLE_LIMIT_EXCEEDED"));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mock_channel_deposit", Integer.class));
        verify(account, never()).acceptDeposit(any(), any(), any());
    }

    @Test
    void unknownChannelReturnsUnknownNotSuccess() throws Exception {
        channel.setMode(MockChannelService.UNKNOWN);
        stubProcessingThen("UNKNOWN");

        MvcResult result = deposit("100.00", "CNY", REQUEST);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("UNKNOWN", objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("status").asText());
        assertEquals("UNKNOWN", jdbc.queryForObject(
                "SELECT result FROM mock_channel_deposit", String.class));
        channel.setMode(MockChannelService.SUCCESS);
        mockMvc.perform(get("/api/v1/transactions/{tradeNo}", tradeNo(result))
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"));
        assertEquals("UNKNOWN", jdbc.queryForObject(
                "SELECT result FROM mock_channel_deposit", String.class));
    }

    @Test
    void otherIdentityCannotReadTradeAndGets404() throws Exception {
        stubProcessingThen("SUCCESS");
        MvcResult result = deposit("100.00", "CNY", REQUEST);

        mockMvc.perform(get("/api/v1/transactions/{tradeNo}", tradeNo(result))
                        .header("Authorization", "Bearer " + OTHER_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRADE_NOT_FOUND"));
    }

    @Test
    void statusAliasSucceededMapsToStoredSuccess() throws Exception {
        stubProcessingThen("SUCCESS");
        MvcResult result = deposit("100.00", "CNY", REQUEST);
        jdbc.update("UPDATE `transaction` SET status = 'SUCCESS' WHERE trade_no = ?", tradeNo(result));

        mockMvc.perform(get("/api/v1/transactions")
                        .param("status", "SUCCEEDED")
                        .param("page", "1")
                        .param("pageSize", "20")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"));
    }
}
