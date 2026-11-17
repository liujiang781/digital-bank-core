package com.digitalbank.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
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
import com.digitalbank.transaction.dto.TransactionDtos.TradeView;
import com.digitalbank.transaction.security.Actor;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 存款编排的组件/功能测试：真实 H2 原单表与模拟渠道表，S2 账户接口和风控接口使用桩。
 * 核对 S3 自己的职责边界：只认领一次、只建一条渠道记录、重放不重启编排、拒绝不产生渠道记录。
 */
@SpringBootTest
@ActiveProfiles("test")
class DepositServiceTest {
    private static final String IDENTITY = "identity-s3-deposit";
    private static final String ACCOUNT = "ACC_S3_DEPOSIT";
    private static final String REQUEST = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Autowired
    private DepositService service;
    @Autowired
    private MockChannelService channel;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AccountClient account;
    @MockitoBean
    private RiskClient risk;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM mock_channel_deposit");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("DELETE FROM account");
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", ACCOUNT);
        channel.setMode(MockChannelService.SUCCESS);
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

    private void stubRiskReject(String reasonCode, String eventId) {
        when(risk.check(any(), any())).thenAnswer(invocation -> {
            CheckRequest request = invocation.getArgument(0);
            return new Decision(request.tradeNo(), "REJECT", reasonCode, eventId);
        });
        when(account.recordFailure(any(), any(), any())).thenAnswer(invocation -> {
            com.digitalbank.transaction.dto.TransactionDtos.Failure failure = invocation.getArgument(0);
            return new Outcome(failure.tradeNo(), "REJECTED", "NONE", reasonCode);
        });
    }

    private void stubAccept(String status) {
        when(account.acceptDeposit(any(), any(), any())).thenAnswer(invocation -> {
            Command command = invocation.getArgument(0);
            return new Outcome(command.tradeNo(), status, "NONE", null);
        });
    }

    private void stubReceipt(String status) {
        when(account.depositResult(any(), any(), any())).thenAnswer(invocation -> {
            DepositResult receipt = invocation.getArgument(0);
            return new Outcome(receipt.tradeNo(), status, "NONE", receipt.reasonCode());
        });
    }

    private void stubOutcome(String status) {
        when(account.outcome(any(), any())).thenAnswer(invocation ->
                new Outcome(invocation.getArgument(0), status, "NONE", null));
    }

    private TradeView deposit(String amount, String currency, String requestId) {
        return service.deposit(new Actor(IDENTITY), ACCOUNT, amount, currency, "实验四存款", requestId, null);
    }

    @Test
    void successfulDepositCreatesOneTradeAndOneChannelRecord() {
        stubActiveSnapshot();
        stubRiskPass();
        stubAccept("PROCESSING");
        stubReceipt("SUCCESS");
        stubOutcome("SUCCESS");

        TradeView view = deposit("100.00", "CNY", REQUEST);

        assertEquals("SUCCESS", view.status());
        assertEquals(1, count("SELECT COUNT(*) FROM `transaction`"));
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT result, evidence_id, reason_code, channel_request_id FROM mock_channel_deposit");
        assertEquals("SUCCESS", record.get("result"));
        assertEquals("EVD" + view.tradeNo(), record.get("evidence_id"));
        assertEquals("CHD" + view.tradeNo(), record.get("channel_request_id"));
        verify(account, times(1)).acceptDeposit(any(), any(), any());
        verify(account, times(1)).depositResult(any(), any(), any());
    }

    @Test
    void replaySameRequestReturnsSameTradeAndDoesNotRestartOrchestration() {
        stubActiveSnapshot();
        stubRiskPass();
        stubAccept("PROCESSING");
        stubReceipt("SUCCESS");
        stubOutcome("SUCCESS");

        TradeView first = deposit("100.00", "CNY", REQUEST);
        TradeView replay = deposit("100.00", "CNY", REQUEST);

        assertEquals(first.tradeNo(), replay.tradeNo());
        assertEquals(1, count("SELECT COUNT(*) FROM `transaction`"));
        assertEquals(1, count("SELECT COUNT(*) FROM mock_channel_deposit"));
        verify(account, times(1)).acceptDeposit(any(), any(), any());
        verify(account, times(1)).depositResult(any(), any(), any());
    }

    @Test
    void sameRequestDifferentAmountConflictsAndKeepsOriginalTrade() {
        stubActiveSnapshot();
        stubRiskPass();
        stubAccept("PROCESSING");
        stubReceipt("SUCCESS");
        stubOutcome("SUCCESS");

        deposit("100.00", "CNY", REQUEST);
        BizException conflict = assertThrows(BizException.class,
                () -> deposit("200.00", "CNY", REQUEST));

        assertEquals(ErrorCode.REQUEST_CONFLICT, conflict.getErrorCode());
        assertEquals(1, count("SELECT COUNT(*) FROM `transaction`"));
        assertEquals(0, new BigDecimal("100.00").compareTo(jdbc.queryForObject(
                "SELECT amount FROM `transaction`", BigDecimal.class)));
    }

    @Test
    void riskRejectRecordsFailureWithoutAcceptOrChannelCall() {
        stubActiveSnapshot();
        stubRiskReject("ACCOUNT_STATUS_INVALID", "RSKEVT-TEST-0001");

        BizException rejected = assertThrows(BizException.class,
                () -> deposit("100.00", "CNY", REQUEST));

        assertEquals(ErrorCode.ACCOUNT_STATUS_INVALID, rejected.getErrorCode());
        verify(account).recordFailure(any(), any(), any());
        verify(account, never()).acceptDeposit(any(), any(), any());
        assertEquals(0, count("SELECT COUNT(*) FROM mock_channel_deposit"));
    }

    @Test
    void failedChannelIsReportedAsFailedAndNeverAsSuccess() {
        channel.setMode(MockChannelService.FAILED);
        stubActiveSnapshot();
        stubRiskPass();
        stubAccept("PROCESSING");
        stubReceipt("FAILED");
        stubOutcome("FAILED");

        TradeView view = deposit("100.00", "CNY", REQUEST);

        assertEquals("FAILED", view.status());
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT result, reason_code, evidence_id FROM mock_channel_deposit");
        assertEquals("FAILED", record.get("result"));
        assertEquals("CHANNEL_DECLINED", record.get("reason_code"));
        assertNotNull(record.get("evidence_id"));
    }

    @Test
    void unknownChannelStaysUnknownAndIsNotRecordedAsSuccess() {
        channel.setMode(MockChannelService.UNKNOWN);
        stubActiveSnapshot();
        stubRiskPass();
        stubAccept("PROCESSING");
        stubReceipt("UNKNOWN");
        stubOutcome("UNKNOWN");

        TradeView view = deposit("100.00", "CNY", REQUEST);

        assertEquals("UNKNOWN", view.status());
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT result, reason_code, evidence_id, next_check_at, reconcile_attempts "
                        + "FROM mock_channel_deposit");
        assertEquals("UNKNOWN", record.get("result"));
        assertEquals("CHANNEL_TIMEOUT", record.get("reason_code"));
        assertNull(record.get("evidence_id"));
        assertNotNull(record.get("next_check_at"));
        assertEquals(0, ((Number) record.get("reconcile_attempts")).intValue());
    }

    @Test
    void accountUnavailableKeepsAcceptedAndDoesNotInventChannelResult() {
        stubActiveSnapshot();
        stubRiskPass();
        when(account.acceptDeposit(any(), any(), any()))
                .thenThrow(new BizException(ErrorCode.INTERNAL_ERROR, "账户服务暂不可用"));
        stubOutcome("ACCEPTED");

        TradeView view = deposit("100.00", "CNY", REQUEST);

        assertEquals("ACCEPTED", view.status());
        assertEquals(0, count("SELECT COUNT(*) FROM mock_channel_deposit"));
        verify(account, never()).depositResult(any(), any(), any());
    }

    @Test
    void accountSideRejectMapsToBusinessErrorWithoutChannelRecord() {
        stubActiveSnapshot();
        stubRiskPass();
        when(account.acceptDeposit(any(), any(), any())).thenAnswer(invocation -> {
            Command command = invocation.getArgument(0);
            return new Outcome(command.tradeNo(), "REJECTED", "NONE", "ACCOUNT_STATUS_INVALID");
        });

        BizException rejected = assertThrows(BizException.class,
                () -> deposit("100.00", "CNY", REQUEST));

        assertEquals(ErrorCode.ACCOUNT_STATUS_INVALID, rejected.getErrorCode());
        assertEquals(0, count("SELECT COUNT(*) FROM mock_channel_deposit"));
    }

    @Test
    void nonCnyDepositIsRejectedBeforeClaim() {
        BizException invalid = assertThrows(BizException.class,
                () -> deposit("100.00", "USD", REQUEST));

        assertEquals(ErrorCode.INVALID_ARGUMENT, invalid.getErrorCode());
        assertEquals(0, count("SELECT COUNT(*) FROM `transaction`"));
        verify(account, never()).snapshot(any(), any());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
