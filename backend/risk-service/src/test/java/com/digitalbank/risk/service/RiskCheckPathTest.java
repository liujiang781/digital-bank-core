package com.digitalbank.risk.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.risk.dto.RiskDtos.CheckRequest;
import com.digitalbank.risk.dto.RiskDtos.CheckRequest.AccountView;
import com.digitalbank.risk.dto.RiskDtos.CheckRequest.PayeeView;
import com.digitalbank.risk.dto.RiskDtos.Decision;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 实验四基本路径法：RiskCheckService.check() 的 9 条独立路径与限额边界。
 * 每个测试方法对应报告中的一条路径或边界用例，断言同时核对返回值和 risk_event 留痕。
 */
@SpringBootTest
@ActiveProfiles("test")
class RiskCheckPathTest {
    private static final String TRADE = "TRDRSKPATH01";
    private static final String IDENTITY = "identity-risk-path";
    private static final String ACCOUNT = "ACC_RPATH01";

    @Autowired
    private RiskCheckService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM risk_event");
        jdbc.update("DELETE FROM risk_rule");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("DELETE FROM account");
        jdbc.update("MERGE INTO account (account_id) KEY (account_id) VALUES (?)", ACCOUNT);
        jdbc.update("INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, "
                + "request_hash, account_id, amount, currency) VALUES (?, ?, 'DEPOSIT', 'req-path', 'x', ?, "
                + "100.00, 'CNY')", TRADE, IDENTITY, ACCOUNT);
        jdbc.update("INSERT INTO risk_rule (rule_id, rule_type, deposit_single_limit, outgoing_single_limit, "
                        + "outgoing_daily_limit, enabled, rule_version) VALUES "
                        + "('RK-01', 'ACCOUNT_STATUS', NULL, NULL, NULL, 1, 1), "
                        + "('RK-02', 'LIMIT_POLICY', 50000.00, 10000.00, 50000.00, 1, 1)");
    }

    private CheckRequest request(String businessType, String amount, String currency,
            String accountStatus, String dailyUsed, PayeeView payee) {
        return new CheckRequest(TRADE, IDENTITY, businessType, amount, currency,
                new AccountView(ACCOUNT, accountStatus, "50000.00", "50000.00", dailyUsed), payee);
    }

    private CheckRequest deposit(String amount) {
        return request("DEPOSIT", amount, "CNY", "ACTIVE", "0.00", null);
    }

    private CheckRequest withdrawal(String amount, String dailyUsed) {
        return request("WITHDRAWAL", amount, "CNY", "ACTIVE", dailyUsed, null);
    }

    @Test
    void path1MissingTradeNoOrAccountIsInvalidArgument() {
        BizException missingTrade = assertThrows(BizException.class,
                () -> service.check(new CheckRequest(null, IDENTITY, "DEPOSIT", "100.00", "CNY",
                        new AccountView(ACCOUNT, "ACTIVE", "50000.00", "50000.00", "0.00"), null)));
        assertEquals(ErrorCode.INVALID_ARGUMENT, missingTrade.getErrorCode());

        BizException missingAccount = assertThrows(BizException.class,
                () -> service.check(new CheckRequest(TRADE, IDENTITY, "DEPOSIT", "100.00", "CNY", null, null)));
        assertEquals(ErrorCode.INVALID_ARGUMENT, missingAccount.getErrorCode());
        assertEquals(0, eventCount());
    }

    @Test
    void path2NonCnyIsInvalidArgument() {
        BizException error = assertThrows(BizException.class,
                () -> service.check(request("DEPOSIT", "100.00", "USD", "ACTIVE", "0.00", null)));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.getErrorCode());
        assertEquals(0, eventCount());
    }

    @Test
    void path3InactiveAccountRejectsWithStatusEvent() {
        Decision decision = service.check(request("DEPOSIT", "100.00", "CNY", "FROZEN", "0.00", null));
        assertEquals("REJECT", decision.result());
        assertEquals("ACCOUNT_STATUS_INVALID", decision.reasonCode());
        assertNotNull(decision.eventId());
        assertEquals("ACCOUNT_STATUS", eventReason(decision.eventId()));
    }

    @Test
    void path4InactivePayeeRejectsTransfer() {
        Decision decision = service.check(request("INTERNAL_TRANSFER", "100.00", "CNY", "ACTIVE", "0.00",
                new PayeeView("ACC_RPATH02", "FROZEN")));
        assertEquals("REJECT", decision.result());
        assertEquals("ACCOUNT_STATUS_INVALID", decision.reasonCode());
        assertNotNull(decision.eventId());
    }

    @Test
    void path5NoEnabledLimitPolicyPasses() {
        jdbc.update("UPDATE risk_rule SET enabled = 0 WHERE rule_type = 'LIMIT_POLICY'");
        Decision decision = service.check(deposit("999999.99"));
        assertEquals("PASS", decision.result());
        assertNull(decision.reasonCode());
        assertEquals(0, eventCount());
    }

    @Test
    void path6SingleLimitExceededRejectsWithEvent() {
        Decision decision = service.check(deposit("50000.01"));
        assertEquals("REJECT", decision.result());
        assertEquals("SINGLE_LIMIT_EXCEEDED", decision.reasonCode());
        assertEquals("SINGLE_LIMIT", eventReason(decision.eventId()));
        assertEquals(1, eventCount());
    }

    @Test
    void path7OutgoingDailyLimitExceededRejects() {
        Decision decision = service.check(withdrawal("2000.00", "49000.00"));
        assertEquals("REJECT", decision.result());
        assertEquals("DAILY_LIMIT_EXCEEDED", decision.reasonCode());
        assertEquals("DAILY_LIMIT", eventReason(decision.eventId()));
    }

    @Test
    void path8OutgoingWithinDailyLimitPasses() {
        Decision decision = service.check(withdrawal("2000.00", "48000.00"));
        assertEquals("PASS", decision.result());
        assertNull(decision.reasonCode());
        assertEquals(0, eventCount());
    }

    @Test
    void path9DepositSkipsDailyLimitCheck() {
        // 存款只校验单笔限额，日累计字段即使已用满也不应拦截
        Decision decision = service.check(request("DEPOSIT", "100.00", "CNY", "ACTIVE", "50000.00", null));
        assertEquals("PASS", decision.result());
        assertEquals(0, eventCount());
    }

    @Test
    void exactSingleLimitAndExactDailyLimitPass() {
        assertEquals("PASS", service.check(deposit("50000.00")).result());
        assertEquals("PASS", service.check(withdrawal("1000.00", "49000.00")).result());
    }

    @Test
    void outgoingWithNullDailyUsedIsTreatedAsZero() {
        Decision decision = service.check(request("WITHDRAWAL", "10000.00", "CNY", "ACTIVE", null, null));
        assertEquals("PASS", decision.result());
    }

    @Test
    void invalidAmountsAreRejectedBeforeRuleEvaluation() {
        List<String> invalid = List.of("0.00", "-1.00", "100", "100.0", "1.234", "abc");
        for (String amount : invalid) {
            BizException error = assertThrows(BizException.class, () -> service.check(deposit(amount)),
                    "金额 " + amount + " 应被拒绝");
            assertEquals(ErrorCode.INVALID_ARGUMENT, error.getErrorCode(), "金额 " + amount);
        }
        BizException nullAmount = assertThrows(BizException.class,
                () -> service.check(deposit(null)));
        assertEquals(ErrorCode.INVALID_ARGUMENT, nullAmount.getErrorCode());
        assertEquals(0, eventCount());
    }

    @Test
    void latestEnabledPolicyWinsAndEventKeepsRuleVersion() {
        jdbc.update("INSERT INTO risk_rule (rule_id, rule_type, deposit_single_limit, outgoing_single_limit, "
                + "outgoing_daily_limit, enabled, rule_version) VALUES "
                + "('RK-09', 'LIMIT_POLICY', 100.00, 100.00, 100.00, 1, 2)");
        Decision decision = service.check(deposit("200.00"));
        assertEquals("REJECT", decision.result());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_event WHERE trade_no = ? AND rule_id = 'RK-09' "
                        + "AND rule_version = 2 AND operator_identity_id = ?",
                Integer.class, TRADE, IDENTITY));
    }

    private int eventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM risk_event", Integer.class);
    }

    private String eventReason(String eventId) {
        return jdbc.queryForObject("SELECT reason_code FROM risk_event WHERE event_id = ?", String.class, eventId);
    }
}
