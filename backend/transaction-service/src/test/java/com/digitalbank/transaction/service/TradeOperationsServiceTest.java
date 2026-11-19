package com.digitalbank.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.transaction.channel.MockChannelService;
import com.digitalbank.transaction.client.AccountClient;
import com.digitalbank.transaction.dto.TransactionDtos.DepositResult;
import com.digitalbank.transaction.dto.TransactionDtos.ExternalTransferResult;
import com.digitalbank.transaction.dto.TransactionDtos.ManualActionResult;
import com.digitalbank.transaction.dto.TransactionDtos.Outcome;
import com.digitalbank.transaction.dto.TransactionDtos.ReasonRequest;
import com.digitalbank.transaction.dto.TransactionDtos.Release;
import com.digitalbank.transaction.security.Actor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * S3 运营接口服务层测试：人工核对沿原渠道上报、恢复重试的明确失败依据、
 * 以及同 requestId 幂等与审计留痕。S2 资金接口用桩替代，原单与渠道表使用真实 H2。
 * 待处理清单复用 GET /api/v1/transactions?status=PENDING，见 TradeQueryStatusAliasTest。
 */
@SpringBootTest
@ActiveProfiles("test")
class TradeOperationsServiceTest {
    private static final String OPS = "identity-ops-s3";
    private static final String RISK = "identity-risk-s3";
    private static final String IN_SCOPE = "ACC_S3_IN";
    private static final String OUT_SCOPE = "ACC_S3_OUT";
    private static final String TRACE = "trace-s3-operation";
    private static final String REASON = "实验四人工处理核查";
    private static final String REQUEST = "11111111-2222-4333-8444-555555555555";

    @Autowired
    private TradeOperationsService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockChannelService channel;

    @MockitoBean
    private AccountClient account;

    private final Actor ops = new Actor(OPS, "customer-ops", Set.of("OPERATIONS"), Set.of(IN_SCOPE));
    private final Actor risk = new Actor(RISK, "customer-risk", Set.of("RISK"), Set.of(IN_SCOPE));

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM trade_manual_action");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM mock_channel_deposit");
        jdbc.update("DELETE FROM `transaction`");
        jdbc.update("DELETE FROM account");
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", IN_SCOPE);
        jdbc.update("MERGE INTO account (account_id, status) KEY (account_id) VALUES (?, 'ACTIVE')", OUT_SCOPE);
        channel.setMode(MockChannelService.SUCCESS);
        stubOutcomeFromLocalTrade();
    }

    /** S2 资金结果是当前状态最终口径：测试桩读取本地原单，模拟下游推进后的状态。 */
    private void stubOutcomeFromLocalTrade() {
        whenOutcome(invocation -> {
            String tradeNo = invocation.getArgument(0);
            List<String> statuses = jdbc.queryForList(
                    "SELECT status FROM `transaction` WHERE trade_no = ?", String.class, tradeNo);
            return new Outcome(tradeNo, statuses.isEmpty() ? "UNKNOWN" : statuses.getFirst(), "NONE", null);
        });
    }

    private void whenOutcome(java.util.function.Function<org.mockito.invocation.InvocationOnMock, Outcome> answer) {
        org.mockito.Mockito.when(account.outcome(any(), any())).thenAnswer(answer::apply);
    }

    private void seedTrade(String tradeNo, String accountId, String businessType, String status,
            String failureReason, String evidenceId) {
        jdbc.update("MERGE INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash, "
                        + "account_id, amount, currency, status, failure_reason, channel_evidence_id) KEY (trade_no) "
                        + "VALUES (?, 'identity-owner', ?, ?, ?, ?, 100.00, 'CNY', ?, ?, ?)",
                tradeNo, businessType, "req-" + tradeNo, "hash-" + tradeNo, accountId,
                status, failureReason, evidenceId);
    }

    private void seedTrade(String tradeNo, String accountId, String businessType, String status) {
        seedTrade(tradeNo, accountId, businessType, status, null, null);
    }

    private void seedChannel(String tradeNo, String accountId, String result,
            String reasonCode, String evidenceId, boolean manualReview, int attempts) {
        jdbc.update("INSERT INTO mock_channel_deposit (trade_no, channel_request_id, account_id, amount, currency, "
                        + "result, evidence_id, reason_code, reconcile_attempts, manual_review_required) "
                        + "VALUES (?, ?, ?, 100.00, 'CNY', ?, ?, ?, ?, ?)",
                tradeNo, "CHD" + tradeNo, accountId, result, evidenceId, reasonCode, attempts, manualReview);
    }

    private ReasonRequest body(String requestId, String reason) {
        return new ReasonRequest(reason, requestId);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Integer.class, action);
    }

    @Test
    void reconcileKeepsUnknownWhenChannelStillUnknown() {
        seedTrade("TRD-R1", IN_SCOPE, "DEPOSIT", "UNKNOWN");
        seedChannel("TRD-R1", IN_SCOPE, "UNKNOWN", "CHANNEL_TIMEOUT", null, true, 1);
        channel.setMode(MockChannelService.UNKNOWN);

        ManualActionResult result = service.reconcile(ops, "TRD-R1", body(REQUEST, REASON), TRACE);

        assertEquals("STILL_UNKNOWN", result.conclusion());
        assertEquals("UNKNOWN", result.status());
        assertEquals(MockChannelService.UNKNOWN, jdbc.queryForObject(
                "SELECT result FROM mock_channel_deposit WHERE trade_no = ?", String.class, "TRD-R1"));
        verify(account, never()).depositResult(any(), any(), any());
        assertEquals(1, auditCount("TRADE_RECONCILE"));
    }

    @Test
    void reconcileReportsDefinitiveResultOnceForReplayedRequest() {
        seedTrade("TRD-R2", IN_SCOPE, "DEPOSIT", "UNKNOWN");
        seedChannel("TRD-R2", IN_SCOPE, "UNKNOWN", "CHANNEL_TIMEOUT", null, true, 1);
        doAnswer(invocation -> {
            DepositResult receipt = invocation.getArgument(0);
            jdbc.update("UPDATE `transaction` SET status = ?, last_channel_result = ? WHERE trade_no = ?",
                    receipt.result(), receipt.result(), receipt.tradeNo());
            return new Outcome(receipt.tradeNo(), receipt.result(), "NONE", null);
        }).when(account).depositResult(any(), any(), any());

        ManualActionResult first = service.reconcile(ops, "TRD-R2", body(REQUEST, REASON), TRACE);
        ManualActionResult replay = service.reconcile(ops, "TRD-R2", body(REQUEST, REASON), TRACE);

        assertEquals("REPORTED_SUCCESS", first.conclusion());
        assertEquals("SUCCESS", first.status());
        assertEquals("SUCCESS", first.channelResult());
        assertEquals(first.tradeNo(), replay.tradeNo());
        assertEquals(first.conclusion(), replay.conclusion());
        assertEquals(first.status(), replay.status());
        verify(account, times(1)).depositResult(any(), any(), any());
        assertEquals(1, auditCount("TRADE_RECONCILE"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_manual_action", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_manual_action "
                + "WHERE result_json LIKE '%REPORTED_SUCCESS%'", Integer.class));
    }

    @Test
    void reconcileSameRequestWithDifferentReasonConflicts() {
        seedTrade("TRD-R3", IN_SCOPE, "DEPOSIT", "UNKNOWN");
        service.reconcile(ops, "TRD-R3", body(REQUEST, REASON), TRACE);

        BizException conflict = assertThrows(BizException.class,
                () -> service.reconcile(ops, "TRD-R3", body(REQUEST, "另一个核查原因"), TRACE));

        assertEquals(ErrorCode.REQUEST_CONFLICT, conflict.getErrorCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_manual_action", Integer.class));
    }

    @Test
    void reconcileRecoveringPointsToRecoverAndRejectsRiskWriter() {
        seedTrade("TRD-R4", IN_SCOPE, "DEPOSIT", "RECOVERING");

        ManualActionResult result = service.reconcile(ops, "TRD-R4", body(REQUEST, REASON), TRACE);
        assertEquals("USE_RECOVER", result.conclusion());
        assertEquals("RECOVERING", result.status());

        seedTrade("TRD-R5", IN_SCOPE, "DEPOSIT", "UNKNOWN");
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                () -> service.reconcile(risk, "TRD-R5", body(REQUEST, REASON), TRACE)).getErrorCode());

        Actor otherScope = new Actor("ops-other", "customer-other", Set.of("OPERATIONS"), Set.of(OUT_SCOPE));
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                () -> service.reconcile(otherScope, "TRD-R5", body(REQUEST, REASON), TRACE)).getErrorCode());
    }

    @Test
    void reconcileWithoutChannelExplainsByLocalFundsOutcome() {
        seedTrade("TRD-R6", IN_SCOPE, "WITHDRAWAL", "UNKNOWN");
        whenOutcome(invocation -> new Outcome(invocation.getArgument(0), "FAILED", "NONE", "LOCAL_DECLINED"));

        ManualActionResult result = service.reconcile(ops, "TRD-R6", body(REQUEST, REASON), TRACE);

        assertEquals("LOCAL_OUTCOME_FAILED", result.conclusion());
        assertEquals("FAILED", result.status());
        verify(account, never()).depositResult(any(), any(), any());
    }

    @Test
    void recoverRejectsTradesWithoutExplicitFailureBasis() {
        seedTrade("TRD-C1", IN_SCOPE, "WITHDRAWAL", "UNKNOWN");
        BizException notRecovering = assertThrows(BizException.class,
                () -> service.recover(ops, "TRD-C1", body(REQUEST, REASON), TRACE));
        assertEquals(ErrorCode.REQUEST_CONFLICT, notRecovering.getErrorCode());

        seedTrade("TRD-C2", IN_SCOPE, "WITHDRAWAL", "RECOVERING", "CHANNEL_TIMEOUT", "EVD-TIMEOUT");
        BizException uncertain = assertThrows(BizException.class,
                () -> service.recover(ops, "TRD-C2", body(REQUEST, REASON), TRACE));
        assertEquals(ErrorCode.REQUEST_CONFLICT, uncertain.getErrorCode());
        verify(account, never()).release(any(), any(), any());
        assertEquals(0, auditCount("TRADE_RECOVERY_RETRY"));
    }

    @Test
    void recoverReleasesLocalReservationOnceWithOriginalEvidence() {
        seedTrade("TRD-C3", IN_SCOPE, "WITHDRAWAL", "RECOVERING", "CHANNEL_DECLINED", "EVD-C3");
        doAnswer(invocation -> {
            Release release = invocation.getArgument(0);
            assertEquals("LOCAL_FAILURE", release.evidenceType());
            assertEquals("CHANNEL_DECLINED", release.reasonCode());
            assertEquals("EVD-C3", release.evidenceId());
            jdbc.update("UPDATE `transaction` SET status = 'FAILED' WHERE trade_no = ?", release.tradeNo());
            return new Outcome(release.tradeNo(), "FAILED", "NONE", null);
        }).when(account).release(any(), any(), any());

        ManualActionResult first = service.recover(ops, "TRD-C3", body(REQUEST, REASON), TRACE);
        ManualActionResult replay = service.recover(ops, "TRD-C3", body(REQUEST, REASON), TRACE);

        assertEquals("RECOVERY_RETRIED", first.conclusion());
        assertEquals("FAILED", first.status());
        assertEquals(first.conclusion(), replay.conclusion());
        verify(account, times(1)).release(any(), any(), any());
        assertEquals(1, auditCount("TRADE_RECOVERY_RETRY"));
    }

    @Test
    void recoverExternalRequiresClearedFailedReceipt() {
        seedTrade("TRD-C4", IN_SCOPE, "EXTERNAL_TRANSFER", "RECOVERING");
        seedChannel("TRD-C4", IN_SCOPE, "UNKNOWN", "CHANNEL_TIMEOUT", null, true, 1);
        assertEquals(ErrorCode.REQUEST_CONFLICT, assertThrows(BizException.class,
                () -> service.recover(ops, "TRD-C4", body(REQUEST, REASON), TRACE)).getErrorCode());

        jdbc.update("UPDATE mock_channel_deposit SET result = 'FAILED', reason_code = 'CHANNEL_DECLINED', "
                + "evidence_id = 'EVD-C4' WHERE trade_no = ?", "TRD-C4");
        doAnswer(invocation -> {
            ExternalTransferResult receipt = invocation.getArgument(0);
            jdbc.update("UPDATE `transaction` SET status = 'FAILED' WHERE trade_no = ?", receipt.tradeNo());
            return new Outcome(receipt.tradeNo(), receipt.result(), "NONE", null);
        }).when(account).externalTransferResult(any(), any(), any());

        ManualActionResult result = service.recover(ops, "TRD-C4", body(REQUEST, REASON), TRACE);
        assertEquals("RECOVERY_RETRIED", result.conclusion());
        assertEquals("FAILED", result.status());
        verify(account, times(1)).externalTransferResult(any(), any(), any());
    }

    @Test
    void writeActionsRejectMissingReasonAndInvalidRequestId() {
        seedTrade("TRD-V1", IN_SCOPE, "DEPOSIT", "UNKNOWN");

        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BizException.class,
                () -> service.reconcile(ops, "TRD-V1", body(REQUEST, " "), TRACE)).getErrorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BizException.class,
                () -> service.reconcile(ops, "TRD-V1", body("not-a-uuid", REASON), TRACE)).getErrorCode());
        assertEquals(ErrorCode.TRADE_NOT_FOUND, assertThrows(BizException.class,
                () -> service.reconcile(ops, "TRD-NOPE", body(REQUEST, REASON), TRACE)).getErrorCode());
        assertFalse(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM trade_manual_action WHERE trade_no = 'TRD-V1')", Boolean.class));
        assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM `transaction`", Integer.class));
    }
}
