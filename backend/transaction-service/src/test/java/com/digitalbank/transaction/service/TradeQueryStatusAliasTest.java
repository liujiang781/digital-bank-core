package com.digitalbank.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.transaction.dto.TransactionDtos.TradePage;
import com.digitalbank.transaction.dto.TransactionDtos.TradeSummary;
import com.digitalbank.transaction.service.IdempotencyService.Claim;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 交易列表状态过滤：前端沿用旧口径的 SUCCEEDED 在查询层映射为 7 态字面量 SUCCESS；
 * 数据库字面量保持 7 态，不因查询别名放宽写入口径。
 */
@SpringBootTest
@ActiveProfiles("test")
class TradeQueryStatusAliasTest {
    private static final String IDENTITY = "identity-q001";
    private static final String ACCOUNT = "ACC_Q001";
    private static final String SUCCESS_TRADE = "TRDQ001SUCCESS";
    private static final String FAILED_TRADE = "TRDQ001FAILED";

    @Autowired
    private TradeQueryService service;
    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM mock_channel_deposit");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("MERGE INTO account (account_id) KEY (account_id) VALUES (?)", ACCOUNT);
        jdbc.update("INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash, "
                        + "account_id, amount, currency, status) VALUES (?, ?, 'DEPOSIT', 'rq-success', ?, ?, ?, 'CNY', 'SUCCESS')",
                SUCCESS_TRADE, IDENTITY, "h".repeat(64), ACCOUNT, new BigDecimal("100.00"));
        jdbc.update("INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash, "
                        + "account_id, amount, currency, status) VALUES (?, ?, 'WITHDRAWAL', 'rq-failed', ?, ?, ?, 'CNY', 'FAILED')",
                FAILED_TRADE, IDENTITY, "g".repeat(64), ACCOUNT, new BigDecimal("50.00"));
    }

    @Test
    void mapsLegacySucceededAliasToSuccess() {
        TradePage page = service.list(IDENTITY, null, "SUCCEEDED", 1, 20, null);
        assertEquals(1, page.total());
        assertEquals(SUCCESS_TRADE, page.items().getFirst().tradeNo());
        assertEquals("SUCCESS", page.items().getFirst().status());
    }

    @Test
    void sevenStateLiteralsStillWork() {
        assertEquals(1, service.list(IDENTITY, null, "SUCCESS", 1, 20, null).total());
        TradePage failed = service.list(IDENTITY, null, "FAILED", 1, 20, null);
        assertEquals(1, failed.total());
        assertEquals(FAILED_TRADE, failed.items().getFirst().tradeNo());
    }

    @Test
    void unknownStatusLiteralsStayRejected() {
        BizException rejected = assertThrows(BizException.class,
                () -> service.list(IDENTITY, null, "PENDING", 1, 20, null));
        assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.getErrorCode());
    }

    @Test
    void aliasDoesNotLoosenClaimFingerprint() {
        // 查询别名与受理指纹无关：同键异内容仍按 7 态口径拒绝
        Claim claim = idempotency.claim(IDENTITY, "WITHDRAWAL", "rq-alias-check",
                "f".repeat(64), ACCOUNT, new BigDecimal("10.00"), "CNY");
        assertTrue(claim.isNew());
        BizException conflict = assertThrows(BizException.class,
                () -> idempotency.claim(IDENTITY, "WITHDRAWAL", "rq-alias-check",
                        "e".repeat(64), ACCOUNT, new BigDecimal("10.00"), "CNY"));
        assertEquals(ErrorCode.REQUEST_CONFLICT, conflict.getErrorCode());
    }
}
