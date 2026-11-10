package com.digitalbank.transaction.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 模拟渠道：CHD+tradeNo 唯一复用；UNKNOWN 按当前模式收敛为明确结果；终态不翻转（T07/T08）。
 */
@SpringBootTest
@ActiveProfiles("test")
class MockChannelServiceTest {
    @Autowired
    private MockChannelService channel;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        // 清理上一条测试的残留（固定 trade_no 需要干净的可 INSERT 状态）
        jdbc.update("DELETE FROM mock_channel_deposit");
        jdbc.update("DELETE FROM `transaction`");
        channel.setMode(MockChannelService.SUCCESS);
    }

    private void seedTrade(String tradeNo) {
        jdbc.update("MERGE INTO account (account_id) KEY (account_id) VALUES (?)", "ACC_T001");
        jdbc.update("INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, "
                        + "request_hash, account_id, amount, currency, status) VALUES (?, 'identity-t001', "
                        + "'DEPOSIT', ?, 'x', 'ACC_T001', 100.00, 'CNY', 'ACCEPTED')",
                tradeNo, tradeNo + "-req");
    }

    @Test
    void createsSingleRecordWithStableChannelRequestId() {
        seedTrade("TRDCH001");
        MockChannelService.ChannelRow first = channel.createOrGet("TRDCH001", "ACC_T001",
                new BigDecimal("100.00"), "CNY");
        channel.createOrGet("TRDCH001", "ACC_T001", new BigDecimal("100.00"), "CNY");
        assertEquals(MockChannelService.channelRequestId("TRDCH001"), first.channelRequestId());
        assertEquals(MockChannelService.SUCCESS, first.result());
        assertEquals("EVDTRDCH001", first.evidenceId());
        assertNull(first.reasonCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_channel_deposit WHERE trade_no = 'TRDCH001'", Integer.class));
    }

    @Test
    void unknownModeSchedulesBoundedReconcile() {
        seedTrade("TRDCH002");
        channel.setMode(MockChannelService.UNKNOWN);
        MockChannelService.ChannelRow row = channel.createOrGet("TRDCH002", "ACC_T001",
                new BigDecimal("100.00"), "CNY");
        assertEquals(MockChannelService.UNKNOWN, row.result());
        assertNull(row.evidenceId());
        assertEquals("CHANNEL_TIMEOUT", row.reasonCode());
        assertNotNull(row.nextCheckAt());
        assertEquals(0, row.reconcileAttempts());
    }

    @Test
    void unknownConvergesToCurrentModeAndNeverFlipsBack() {
        seedTrade("TRDCH003");
        channel.setMode(MockChannelService.UNKNOWN);
        channel.createOrGet("TRDCH003", "ACC_T001", new BigDecimal("100.00"), "CNY");
        // 渠道侧仍未知
        assertEquals(MockChannelService.UNKNOWN, channel.queryForReconcile("TRDCH003").result());
        // 模式切为明确结果后核对收敛
        channel.setMode(MockChannelService.SUCCESS);
        MockChannelService.ChannelRow converged = channel.queryForReconcile("TRDCH003");
        assertEquals(MockChannelService.SUCCESS, converged.result());
        assertEquals("EVDTRDCH003", converged.evidenceId());
        // 终态不再翻转
        channel.setMode(MockChannelService.FAILED);
        assertEquals(MockChannelService.SUCCESS, channel.queryForReconcile("TRDCH003").result());
    }

    @Test
    void progressIsPersistedAcrossReads() {
        seedTrade("TRDCH004");
        channel.setMode(MockChannelService.UNKNOWN);
        channel.createOrGet("TRDCH004", "ACC_T001", new BigDecimal("100.00"), "CNY");
        channel.markReconcileProgress("TRDCH004", 3, null, true);
        MockChannelService.ChannelRow row = channel.find("TRDCH004");
        assertEquals(3, row.reconcileAttempts());
        assertTrue(row.manualReviewRequired());
        assertNull(row.nextCheckAt());
    }
}
