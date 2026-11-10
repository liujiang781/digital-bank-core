package com.digitalbank.risk.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.digitalbank.risk.dto.RiskDtos.CheckRequest;
import com.digitalbank.risk.dto.RiskDtos.CheckRequest.AccountView;
import com.digitalbank.risk.dto.RiskDtos.CheckRequest.PayeeView;
import com.digitalbank.risk.dto.RiskDtos.Decision;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 风控求值短路顺序：状态 → 单笔 → 日累计；REJECT 落 risk_event，同单同规则幂等；PASS 不落事件（T15）。
 */
@SpringBootTest
@ActiveProfiles("test")
class RiskCheckServiceTest {
    @Autowired
    private RiskCheckService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String TRADE = "TRDRSK001";

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM risk_event");
        jdbc.update("DELETE FROM risk_rule");
        jdbc.update("MERGE INTO account (account_id) KEY (account_id) VALUES (?)", "ACC_R001");
        jdbc.update("MERGE INTO `transaction` (trade_no, identity_id, business_type, request_id, "
                + "request_hash, account_id, amount, currency) KEY (trade_no) "
                + "VALUES (?, 'identity-r001', 'DEPOSIT', 'req-1', 'x', 'ACC_R001', 100.00, 'CNY')", TRADE);
        jdbc.update("INSERT INTO risk_rule (rule_id, rule_type, deposit_single_limit, outgoing_single_limit, "
                        + "outgoing_daily_limit, enabled, rule_version) VALUES "
                        + "('RK-01', 'ACCOUNT_STATUS', NULL, NULL, NULL, 1, 1), "
                        + "('RK-02', 'LIMIT_POLICY', 50000.00, 10000.00, 50000.00, 1, 1)");
    }

    private CheckRequest deposit(String amount, String accountStatus) {
        return new CheckRequest(TRADE, "identity-r001", "DEPOSIT", amount, "CNY",
                new AccountView("ACC_R001", accountStatus, "50000.00", "50000.00", "0.00"), null);
    }

    private CheckRequest withdrawal(String amount, String used) {
        return new CheckRequest(TRADE, "identity-r001", "WITHDRAWAL", amount, "CNY",
                new AccountView("ACC_R001", "ACTIVE", "10000.00", "50000.00", used), null);
    }

    @Test
    void activeDepositWithinLimitPassesWithoutEvent() {
        Decision decision = service.check(deposit("100.00", "ACTIVE"));
        assertEquals("PASS", decision.result());
        assertNull(decision.reasonCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_event", Integer.class));
    }

    @Test
    void frozenAccountRejectsBeforeLimitsWithEvent() {
        Decision decision = service.check(deposit("100.00", "FROZEN"));
        assertEquals("REJECT", decision.result());
        assertEquals("ACCOUNT_STATUS_INVALID", decision.reasonCode());
        assertNotNull(decision.eventId());
        assertEquals("ACCOUNT_STATUS", jdbc.queryForObject(
                "SELECT reason_code FROM risk_event WHERE event_id = ?", String.class, decision.eventId()));
    }

    @Test
    void depositOverSingleLimitRejects() {
        Decision decision = service.check(deposit("50000.01", "ACTIVE"));
        assertEquals("REJECT", decision.result());
        assertEquals("SINGLE_LIMIT_EXCEEDED", decision.reasonCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_event WHERE trade_no = ?", Integer.class, TRADE));
    }

    @Test
    void repeatedRejectReusesSameEventId() {
        Decision first = service.check(deposit("50000.01", "ACTIVE"));
        Decision second = service.check(deposit("50000.01", "ACTIVE"));
        assertEquals(first.eventId(), second.eventId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_event WHERE trade_no = ?", Integer.class, TRADE));
    }

    @Test
    void withdrawalOverSingleLimitRejects() {
        Decision decision = service.check(withdrawal("10000.01", "0.00"));
        assertEquals("REJECT", decision.result());
        assertEquals("SINGLE_LIMIT_EXCEEDED", decision.reasonCode());
    }

    @Test
    void withdrawalOverDailyLimitRejects() {
        Decision decision = service.check(withdrawal("2000.00", "49000.00"));
        assertEquals("REJECT", decision.result());
        assertEquals("DAILY_LIMIT_EXCEEDED", decision.reasonCode());
    }

    @Test
    void withdrawalWithinLimitsPasses() {
        Decision decision = service.check(withdrawal("2000.00", "48000.00"));
        assertEquals("PASS", decision.result());
    }

    @Test
    void disabledPolicyFallsBackToPass() {
        jdbc.update("UPDATE risk_rule SET enabled = 0 WHERE rule_id = 'RK-02'");
        assertEquals("PASS", service.check(deposit("999999.99", "ACTIVE")).result());
    }

    @Test
    void frozenPayeeRejectsOutgoingTransfer() {
        Decision decision = service.check(new CheckRequest(TRADE, "identity-r001", "INTERNAL_TRANSFER",
                "100.00", "CNY", new AccountView("ACC_R001", "ACTIVE", "10000.00", "50000.00", "0.00"),
                new PayeeView("ACC_R002", "FROZEN")));
        assertEquals("REJECT", decision.result());
        assertEquals("ACCOUNT_STATUS_INVALID", decision.reasonCode());
    }
}
