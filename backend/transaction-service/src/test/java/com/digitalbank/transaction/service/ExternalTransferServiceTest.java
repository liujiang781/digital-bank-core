package com.digitalbank.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 跨行转账单写入（S2-S3 跨行交接文档第 1 节）：认领原单后先落 transfer_order，
 * transfer_type=EXTERNAL、payee_account_id 为空、clearing_status 初始 SUBMITTED、一交易一单；
 * 重复写入与并发撞唯一键都保留先到要素；转账单缺失时恢复转人工，不凭猜测补要素。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExternalTransferServiceTest {
    private static final String IDENTITY = "identity-t201";
    private static final String PAYER = "ACC_T201_P";
    private static final String BANK = "TESTBANK";
    private static final String REF = "masked-ref-1234";

    @Autowired
    private ExternalTransferService service;
    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM mock_channel_deposit");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("MERGE INTO account (account_id) KEY (account_id) VALUES (?)", PAYER);
    }

    @Test
    void writesExternalTransferOrderOncePerClaimedTrade() {
        Claim claim = idempotency.claim(IDENTITY, "EXTERNAL_TRANSFER", "11111111-2222-3333-4444-555555555555",
                "a".repeat(64), PAYER, new BigDecimal("100.00"), "CNY");
        service.ensureTransferOrder(claim.tradeNo(), PAYER, BANK, REF, new BigDecimal("100.00"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT transfer_id, transfer_type, payer_account_id, payee_account_id, external_bank_code, "
                        + "external_payee_ref, amount, currency, clearing_status "
                        + "FROM transfer_order WHERE trade_no = ?", claim.tradeNo());
        assertEquals("EXTERNAL", row.get("transfer_type"));
        assertEquals(PAYER, row.get("payer_account_id"));
        assertNull(row.get("payee_account_id"));
        assertEquals(BANK, row.get("external_bank_code"));
        assertEquals(REF, row.get("external_payee_ref"));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) row.get("amount")));
        assertEquals("CNY", row.get("currency"));
        assertEquals("SUBMITTED", row.get("clearing_status"));
        assertTrue(Objects.toString(row.get("transfer_id"), "").startsWith("TFR"),
                "transfer_id 应使用 TFR 前缀，实际为 " + row.get("transfer_id"));
    }

    @Test
    void repeatedWriteKeepsFirstElements() {
        Claim claim = idempotency.claim(IDENTITY, "EXTERNAL_TRANSFER", "22222222-3333-4444-5555-666666666666",
                "b".repeat(64), PAYER, new BigDecimal("30.00"), "CNY");
        service.ensureTransferOrder(claim.tradeNo(), PAYER, BANK, REF, new BigDecimal("30.00"));
        // 重复编排/换收款方重试都不覆盖既有要素：S2 受理后不得更换交易要素
        service.ensureTransferOrder(claim.tradeNo(), PAYER, "OTHERBANK", "other-ref", new BigDecimal("30.00"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, claim.tradeNo()));
        assertEquals(BANK, jdbc.queryForObject(
                "SELECT external_bank_code FROM transfer_order WHERE trade_no = ?", String.class, claim.tradeNo()));
        assertEquals(REF, jdbc.queryForObject(
                "SELECT external_payee_ref FROM transfer_order WHERE trade_no = ?", String.class, claim.tradeNo()));
    }

    @Test
    void missingTransferOrderBlocksResumeInsteadOfGuessing() {
        // 原单存在但转账单缺失（认领后未及写入即中断）：恢复不得凭空补要素，转人工核对
        Claim claim = idempotency.claim(IDENTITY, "EXTERNAL_TRANSFER", "33333333-4444-5555-6666-777777777777",
                "c".repeat(64), PAYER, new BigDecimal("10.00"), "CNY");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, claim.tradeNo()));
        service.resume(idempotency.readTrade(claim.tradeNo()), null);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, claim.tradeNo()));
    }

    @Test
    void resumeReusesRegisteredOrderAndNeverRewrites() {
        Claim claim = idempotency.claim(IDENTITY, "EXTERNAL_TRANSFER", "44444444-5555-6666-7777-888888888888",
                "d".repeat(64), PAYER, new BigDecimal("20.00"), "CNY");
        service.ensureTransferOrder(claim.tradeNo(), PAYER, BANK, REF, new BigDecimal("20.00"));
        // 恢复只沿已登记转账单推进（下游不可用只记日志），不重写转账单
        service.resume(idempotency.readTrade(claim.tradeNo()), null);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE trade_no = ?", Integer.class, claim.tradeNo()));
        assertEquals(BANK, jdbc.queryForObject(
                "SELECT external_bank_code FROM transfer_order WHERE trade_no = ?", String.class, claim.tradeNo()));
    }
}
