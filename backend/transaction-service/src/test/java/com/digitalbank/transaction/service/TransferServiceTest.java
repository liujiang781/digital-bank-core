package com.digitalbank.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalbank.transaction.service.IdempotencyService.Claim;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 转账单写入（交接文档第 6 节）：认领原单后先落 transfer_order，一交易一单；
 * 重复写入与并发撞唯一键都保留先到要素，S2 reserve 复核双方与金额不得被更换。
 */
@SpringBootTest
@ActiveProfiles("test")
class TransferServiceTest {
    private static final String IDENTITY = "identity-t101";
    private static final String PAYER = "ACC_T101_P";
    private static final String PAYEE = "ACC_T101_R";
    private static final String OTHER = "ACC_T101_X";

    @Autowired
    private TransferService service;
    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        // H2 内存库与 Spring 上下文在 JVM 内共享，先清理上一条测试的残留，保证写入顺序无关
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM mock_channel_deposit");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("DELETE FROM account");
        jdbc.update("MERGE INTO account (account_id) KEY (account_id) VALUES (?)", PAYER);
        jdbc.update("MERGE INTO account (account_id) KEY (account_id) VALUES (?)", PAYEE);
        jdbc.update("MERGE INTO account (account_id) KEY (account_id) VALUES (?)", OTHER);
    }

    @Test
    void writesOneTransferOrderPerClaimedTrade() {
        Claim claim = idempotency.claim(IDENTITY, "INTERNAL_TRANSFER", "11111111-2222-3333-4444-555555555555",
                "a".repeat(64), PAYER, new BigDecimal("100.00"), "CNY");
        service.ensureTransferOrder(claim.tradeNo(), PAYER, PAYEE, new BigDecimal("100.00"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT transfer_id, transfer_type, payer_account_id, payee_account_id, amount, currency, "
                        + "clearing_status FROM transfer_order WHERE trade_no = ?", claim.tradeNo());
        assertEquals("INTERNAL", row.get("transfer_type"));
        assertEquals(PAYER, row.get("payer_account_id"));
        assertEquals(PAYEE, row.get("payee_account_id"));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) row.get("amount")));
        assertEquals("CNY", row.get("currency"));
        assertEquals("NOT_APPLICABLE", row.get("clearing_status"));
        assertTrue(Objects.toString(row.get("transfer_id"), "").startsWith("TFR"),
                "transfer_id 应使用 TFR 前缀，实际为 " + row.get("transfer_id"));
    }

    @Test
    void repeatedWriteKeepsFirstElements() {
        Claim claim = idempotency.claim(IDENTITY, "INTERNAL_TRANSFER", "22222222-3333-4444-5555-666666666666",
                "b".repeat(64), PAYER, new BigDecimal("30.00"), "CNY");
        service.ensureTransferOrder(claim.tradeNo(), PAYER, PAYEE, new BigDecimal("30.00"));
        // 重复编排/换收款方重试都不覆盖既有要素：S2 受理后不得更换交易要素
        service.ensureTransferOrder(claim.tradeNo(), PAYER, OTHER, new BigDecimal("30.00"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, claim.tradeNo()));
        assertEquals(PAYEE, jdbc.queryForObject(
                "SELECT payee_account_id FROM transfer_order WHERE trade_no = ?", String.class, claim.tradeNo()));
    }

    @Test
    void missingTransferOrderBlocksResumeInsteadOfGuessing() {
        // 原单存在但转账单缺失（认领后未及写入即中断）：恢复不得凭空补要素，转人工核对
        Claim claim = idempotency.claim(IDENTITY, "INTERNAL_TRANSFER", "33333333-4444-5555-6666-777777777777",
                "c".repeat(64), PAYER, new BigDecimal("10.00"), "CNY");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, claim.tradeNo()));
        service.resume(idempotency.readTrade(claim.tradeNo()), null);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, claim.tradeNo()));
    }

    @Test
    void resumeReusesRegisteredPayeeAndNeverRewrites() {
        Claim claim = idempotency.claim(IDENTITY, "INTERNAL_TRANSFER", "44444444-5555-6666-7777-888888888888",
                "d".repeat(64), PAYER, new BigDecimal("20.00"), "CNY");
        service.ensureTransferOrder(claim.tradeNo(), PAYER, PAYEE, new BigDecimal("20.00"));
        // 恢复只沿已登记转账单推进（下游不可用只记日志），不重写转账单
        service.resume(idempotency.readTrade(claim.tradeNo()), null);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, claim.tradeNo()));
        assertEquals(PAYEE, jdbc.queryForObject(
                "SELECT payee_account_id FROM transfer_order WHERE trade_no = ?", String.class, claim.tradeNo()));
        assertThrowsBiz(() -> idempotency.claim(IDENTITY, "INTERNAL_TRANSFER",
                "44444444-5555-6666-7777-888888888888", "x".repeat(64), PAYER,
                new BigDecimal("20.00"), "CNY"), com.digitalbank.common.api.ErrorCode.REQUEST_CONFLICT);
    }

    private void assertThrowsBiz(Runnable action, com.digitalbank.common.api.ErrorCode expected) {
        try {
            action.run();
            org.junit.jupiter.api.Assertions.fail("应抛出 " + expected);
        } catch (com.digitalbank.common.exception.BizException e) {
            assertEquals(expected, e.getErrorCode());
        }
    }
}
