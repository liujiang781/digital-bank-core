package com.digitalbank.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.transaction.service.IdempotencyService.Claim;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 三元组认领：同键同内容返回原单，同键异内容返回 409；唯一索引兜底（T02/T03）。 */
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyServiceTest {
    private static final String IDENTITY = "identity-t001";
    private static final String ACCOUNT = "ACC_T001";
    private static final String REQUEST = "11111111-2222-3333-4444-555555555555";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    private IdempotencyService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        // H2 内存库与 Spring 上下文在 JVM 内共享，先清理上一条测试的残留，保证认领顺序无关
        jdbc.update("DELETE FROM mock_channel_deposit");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("MERGE INTO account (account_id) KEY (account_id) VALUES (?)", ACCOUNT);
    }

    @Test
    void claimsNewTradeAsAccepted() {
        Claim claim = service.claim(IDENTITY, "DEPOSIT", REQUEST, HASH_A, ACCOUNT,
                new BigDecimal("100.00"), "CNY");
        assertTrue(claim.isNew());
        assertEquals("ACCEPTED", claim.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM `transaction` WHERE trade_no = ?", Integer.class, claim.tradeNo()));
    }

    @Test
    void sameKeySameContentReturnsOriginalTrade() {
        Claim first = service.claim(IDENTITY, "DEPOSIT", REQUEST, HASH_A, ACCOUNT,
                new BigDecimal("100.00"), "CNY");
        Claim second = service.claim(IDENTITY, "DEPOSIT", REQUEST, HASH_A, ACCOUNT,
                new BigDecimal("100.00"), "CNY");
        assertFalse(second.isNew());
        assertEquals(first.tradeNo(), second.tradeNo());
    }

    @Test
    void sameKeyDifferentContentConflicts() {
        service.claim(IDENTITY, "DEPOSIT", REQUEST, HASH_A, ACCOUNT, new BigDecimal("100.00"), "CNY");
        BizException conflict = assertThrows(BizException.class,
                () -> service.claim(IDENTITY, "DEPOSIT", REQUEST, HASH_B, ACCOUNT,
                        new BigDecimal("200.00"), "CNY"));
        assertEquals(ErrorCode.REQUEST_CONFLICT, conflict.getErrorCode());
    }

    @Test
    void differentRequestIdsCreateSeparateTrades() {
        Claim first = service.claim(IDENTITY, "DEPOSIT", REQUEST, HASH_A, ACCOUNT,
                new BigDecimal("100.00"), "CNY");
        Claim second = service.claim(IDENTITY, "DEPOSIT",
                "66666666-7777-8888-9999-000000000000", HASH_A, ACCOUNT,
                new BigDecimal("100.00"), "CNY");
        assertTrue(second.isNew());
        assertFalse(first.tradeNo().equals(second.tradeNo()));
    }
}
