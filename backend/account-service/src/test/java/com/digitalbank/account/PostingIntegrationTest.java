package com.digitalbank.account;

import com.digitalbank.account.dto.PostingDtos.Command;
import com.digitalbank.account.dto.PostingDtos.DepositResult;
import com.digitalbank.account.dto.PostingDtos.Failure;
import com.digitalbank.account.dto.PostingDtos.Outcome;
import com.digitalbank.account.dto.PostingDtos.Release;
import com.digitalbank.account.security.Actor;
import com.digitalbank.account.service.PostingService;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real local transactions against H2; MySQL runtime verification remains a separate check. */
@SpringBootTest(properties = "account.internal-token=s2-test-service-token-32-bytes-long")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostingIntegrationTest {
    @Autowired PostingService postings;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void seed() {
        // Tests intentionally do not run in an enclosing test transaction: commits and row locks are real.
        for (String table : List.of("account_command", "account_reservation", "risk_event", "risk_rule",
                "transaction_flow", "transfer_order", "`transaction`", "account_status_log", "audit_log",
                "account", "credential", "customer")) {
            jdbc.update("DELETE FROM " + table);
        }
        customer("customer-1", "identity-1");
        customer("customer-2", "identity-2");
        account("payer", "customer-1", "500.00");
        account("payee", "customer-2", "20.00");
    }

    @Test
    void repeatedAndConcurrentSuccessfulDepositHasOneMoneyEffect() throws Exception {
        deposit("deposit-1", "100.00");
        Command command = depositCommand("deposit-1", "100.00");
        assertThat(postings.acceptDeposit(command).status()).isEqualTo("PROCESSING");
        var acceptedAt = jdbc.queryForObject("SELECT accepted_at FROM `transaction` WHERE trade_no = ?",
                java.time.LocalDateTime.class, "deposit-1");
        postings.acceptDeposit(command);
        assertThat(jdbc.queryForObject("SELECT accepted_at FROM `transaction` WHERE trade_no = ?",
                java.time.LocalDateTime.class, "deposit-1")).isEqualTo(acceptedAt);

        DepositResult result = result("deposit-1", "100.00", "SUCCESS", "evidence-1", null);
        List<Outcome> outcomes = concurrent(8, () -> postings.depositResult(result));
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo("SUCCESS"));
        assertThat(postings.completeDeposit(result).status()).isEqualTo("SUCCESS");
        assertMoney("payer", "600.00", "0.00");
        assertThat(flowCount("deposit-1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT entry_role FROM transaction_flow WHERE trade_no = ?",
                String.class, "deposit-1")).isEqualTo("DEPOSIT_IN");
        assertThat(tradeStatus("deposit-1")).isEqualTo("SUCCESS");
    }

    @Test
    void unknownDepositCanLaterSucceedAndLateUnknownCannotEraseTerminalEvidence() {
        deposit("deposit-unknown", "100.00");
        postings.acceptDeposit(depositCommand("deposit-unknown", "100.00"));
        assertThat(postings.depositResult(result("deposit-unknown", "100.00", "UNKNOWN", null,
                "CHANNEL_TIMEOUT")).status()).isEqualTo("UNKNOWN");
        assertMoney("payer", "500.00", "0.00");
        assertThat(flowCount("deposit-unknown")).isZero();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM `transaction` WHERE trade_no = ?",
                java.time.LocalDateTime.class, "deposit-unknown")).isNull();

        postings.depositResult(result("deposit-unknown", "100.00", "SUCCESS", "stable-evidence", null));
        assertThat(postings.depositResult(result("deposit-unknown", "100.00", "UNKNOWN", null,
                "CHANNEL_TIMEOUT")).status()).isEqualTo("SUCCESS");
        assertMoney("payer", "600.00", "0.00");
        assertThat(flowCount("deposit-unknown")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT channel_evidence_id FROM `transaction` WHERE trade_no = ?",
                String.class, "deposit-unknown")).isEqualTo("stable-evidence");
        assertThat(jdbc.queryForObject("SELECT last_channel_result FROM `transaction` WHERE trade_no = ?",
                String.class, "deposit-unknown")).isEqualTo("SUCCESS");
    }

    @Test
    void freezeBeforeAcceptancePersistsRejectionWhileFreezeAfterAcceptanceAllowsConfirmation() {
        deposit("deposit-frozen", "100.00");
        jdbc.update("UPDATE account SET status = 'FROZEN' WHERE account_id = 'payer'");
        Outcome rejection = postings.acceptDeposit(depositCommand("deposit-frozen", "100.00"));
        assertThat(rejection.status()).isEqualTo("REJECTED");
        assertThat(rejection.reasonCode()).isEqualTo("ACCOUNT_STATUS_INVALID");
        assertThat(tradeStatus("deposit-frozen")).isEqualTo("REJECTED");
        assertMoney("payer", "500.00", "0.00");
        assertThat(flowCount("deposit-frozen")).isZero();

        jdbc.update("UPDATE account SET status = 'ACTIVE' WHERE account_id = 'payer'");
        deposit("deposit-accepted", "100.00");
        postings.acceptDeposit(depositCommand("deposit-accepted", "100.00"));
        jdbc.update("UPDATE account SET status = 'FROZEN' WHERE account_id = 'payer'");
        assertThat(postings.depositResult(result("deposit-accepted", "100.00", "SUCCESS", "late-success", null))
                .status()).isEqualTo("SUCCESS");
        assertMoney("payer", "600.00", "0.00");
        assertThat(flowCount("deposit-accepted")).isEqualTo(1);
    }

    @Test
    void depositEvidenceMustMatchAcceptedTradeAndCannotFlipFinalResult() {
        deposit("deposit-conflict", "100.00");
        postings.acceptDeposit(depositCommand("deposit-conflict", "100.00"));
        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.depositResult(
                result("deposit-conflict", "101.00", "SUCCESS", "mismatched", null)));
        assertMoney("payer", "500.00", "0.00");
        assertThat(tradeStatus("deposit-conflict")).isEqualTo("PROCESSING");

        postings.depositResult(result("deposit-conflict", "100.00", "SUCCESS", "valid-success", null));
        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.depositResult(
                result("deposit-conflict", "100.00", "FAILED", "contradictory-failure", "CHANNEL_DECLINED")));
        assertMoney("payer", "600.00", "0.00");
        assertThat(tradeStatus("deposit-conflict")).isEqualTo("SUCCESS");
        assertThat(flowCount("deposit-conflict")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE trade_no = ?",
                Long.class, "deposit-conflict")).isGreaterThan(0);
    }

    @Test
    void explicitDepositFailureHasNoBalanceOrFlowAndCannotLaterCreditMoney() {
        deposit("deposit-failed", "100.00");
        postings.acceptDeposit(depositCommand("deposit-failed", "100.00"));
        assertThat(postings.depositResult(result("deposit-failed", "100.00", "FAILED", "failed-evidence",
                "CHANNEL_DECLINED")).status()).isEqualTo("FAILED");
        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.depositResult(
                result("deposit-failed", "100.00", "SUCCESS", "contradictory-success", null)));
        assertMoney("payer", "500.00", "0.00");
        assertThat(flowCount("deposit-failed")).isZero();
        assertThat(tradeStatus("deposit-failed")).isEqualTo("FAILED");
    }

    @Test
    void aReceiptCannotSkipAccountAcceptanceAndAWithdrawalCannotSkipReservation() {
        deposit("deposit-unaccepted", "100.00");
        assertThatThrownBy(() -> postings.depositResult(
                result("deposit-unaccepted", "100.00", "SUCCESS", "premature-evidence", null)))
                .isInstanceOf(BizException.class);
        withdrawal("withdraw-unreserved", "100.00");
        assertThatThrownBy(() -> postings.completeWithdrawal(outgoingCommand("withdraw-unreserved", "100.00", null)))
                .isInstanceOf(BizException.class);
        assertMoney("payer", "500.00", "0.00");
        assertThat(flowCount("deposit-unaccepted")).isZero();
        assertThat(flowCount("withdraw-unreserved")).isZero();
        assertThat(tradeStatus("deposit-unaccepted")).isEqualTo("ACCEPTED");
        assertThat(tradeStatus("withdraw-unreserved")).isEqualTo("ACCEPTED");
    }

    @Test
    void reserveAndWithdrawalSubmissionAreIndividuallyIdempotent() {
        withdrawal("withdraw-1", "100.00");
        Command command = outgoingCommand("withdraw-1", "100.00", null);
        assertThat(postings.reserve(command).reservationStatus()).isEqualTo("RESERVED");
        postings.reserve(command);
        assertMoney("payer", "500.00", "100.00");
        assertThat(flowCount("withdraw-1")).isZero();
        assertThat(postings.completeWithdrawal(command).status()).isEqualTo("SUCCESS");
        postings.completeWithdrawal(command);
        assertMoney("payer", "400.00", "0.00");
        assertThat(flowCount("withdraw-1")).isEqualTo(1);
        assertThat(postings.outcome("withdraw-1").reservationStatus()).isEqualTo("CONSUMED");
    }

    @Test
    void releasingOneTradeCannotConsumeAnotherTradesReservation() {
        withdrawal("withdraw-release", "100.00");
        withdrawal("withdraw-other", "30.00");
        postings.reserve(outgoingCommand("withdraw-release", "100.00", null));
        postings.reserve(outgoingCommand("withdraw-other", "30.00", null));
        Release release = new Release("withdraw-release", "payer", "100.00", "CNY",
                "LOCAL_PROCESSING_FAILED", "LOCAL_FAILURE", "local-failure-1");
        assertThat(postings.release(release).reservationStatus()).isEqualTo("RELEASED");
        assertThat(postings.release(release).status()).isEqualTo("FAILED");
        assertMoney("payer", "500.00", "30.00");
        assertThat(postings.outcome("withdraw-other").reservationStatus()).isEqualTo("RESERVED");
        assertThat(flowCount("withdraw-release")).isZero();
        assertThat(postings.reserve(outgoingCommand("withdraw-release", "100.00", null)).status())
                .isEqualTo("FAILED");
        assertMoney("payer", "500.00", "30.00");
    }

    @Test
    void recoveringOutgoingTradesRejectLateSuccessAndRetainTheirReservations() {
        withdrawal("recovering-withdrawal", "100.00");
        transfer("recovering-transfer", "50.00");
        Command withdrawal = outgoingCommand("recovering-withdrawal", "100.00", null);
        Command transfer = outgoingCommand("recovering-transfer", "50.00", "payee");
        postings.reserve(withdrawal);
        postings.reserve(transfer);
        jdbc.update("UPDATE `transaction` SET status = 'RECOVERING', failure_reason = 'LOCAL_PROCESSING_FAILED', "
                + "channel_evidence_id = 'original-failure-evidence' WHERE trade_no IN (?, ?)",
                "recovering-withdrawal", "recovering-transfer");

        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.completeWithdrawal(withdrawal));
        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.postInternalTransfer(transfer));
        assertMoney("payer", "500.00", "150.00");
        assertMoney("payee", "20.00", "0.00");
        assertThat(tradeStatus("recovering-withdrawal")).isEqualTo("RECOVERING");
        assertThat(tradeStatus("recovering-transfer")).isEqualTo("RECOVERING");
        assertThat(postings.outcome("recovering-withdrawal").reservationStatus()).isEqualTo("RESERVED");
        assertThat(postings.outcome("recovering-transfer").reservationStatus()).isEqualTo("RESERVED");
        assertThat(flowCount("recovering-withdrawal")).isZero();
        assertThat(flowCount("recovering-transfer")).isZero();
    }

    @Test
    void recoveringFailureMustReuseOriginalEvidenceAndOnlyThatEvidenceCanReleaseMoney() {
        withdrawal("recovering-evidence", "100.00");
        postings.reserve(outgoingCommand("recovering-evidence", "100.00", null));
        jdbc.update("UPDATE `transaction` SET status = 'RECOVERING', failure_reason = 'LOCAL_PROCESSING_FAILED', "
                + "channel_evidence_id = 'original-failure-evidence' WHERE trade_no = 'recovering-evidence'");
        long auditsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE trade_no = ?",
                Long.class, "recovering-evidence");
        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.recordFailure(new Failure("recovering-evidence", "FAILED",
                "LOCAL_PROCESSING_FAILED", "LOCAL_FAILURE", "different-failure-evidence")));
        long auditsAfterEvidenceConflict = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE trade_no = ?",
                Long.class, "recovering-evidence");
        assertThat(auditsAfterEvidenceConflict).isGreaterThan(auditsBefore);
        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.recordFailure(new Failure("recovering-evidence", "FAILED",
                "DIFFERENT_LOCAL_FAILURE", "LOCAL_FAILURE", "original-failure-evidence")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE trade_no = ?",
                Long.class, "recovering-evidence")).isGreaterThan(auditsAfterEvidenceConflict);
        assertMoney("payer", "500.00", "100.00");
        assertThat(tradeStatus("recovering-evidence")).isEqualTo("RECOVERING");
        assertThat(postings.outcome("recovering-evidence").reservationStatus()).isEqualTo("RESERVED");

        Release original = new Release("recovering-evidence", "payer", "100.00", "CNY", "LOCAL_PROCESSING_FAILED",
                "LOCAL_FAILURE", "original-failure-evidence");
        assertThat(postings.release(original).status()).isEqualTo("FAILED");
        assertThat(postings.release(original).reservationStatus()).isEqualTo("RELEASED");
        assertMoney("payer", "500.00", "0.00");
        assertThat(flowCount("recovering-evidence")).isZero();
    }

    @Test
    void transferFailureAtSecondFlowRollsBackBothAccountsAndCanBeSafelyRetried() {
        transfer("transfer-rollback", "100.00");
        Command command = outgoingCommand("transfer-rollback", "100.00", "payee");
        postings.reserve(command);
        jdbc.execute("ALTER TABLE transaction_flow ADD CONSTRAINT test_reject_transfer_in "
                + "CHECK (entry_role <> 'TRANSFER_IN')");
        try {
            assertThatThrownBy(() -> postings.postInternalTransfer(command)).isInstanceOf(DataAccessException.class);
        } finally {
            String product = jdbc.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName());
            jdbc.execute("ALTER TABLE transaction_flow DROP " + ("MySQL".equals(product) ? "CHECK" : "CONSTRAINT")
                    + " test_reject_transfer_in");
        }
        assertMoney("payer", "500.00", "100.00");
        assertMoney("payee", "20.00", "0.00");
        assertThat(flowCount("transfer-rollback")).isZero();
        assertThat(tradeStatus("transfer-rollback")).isEqualTo("PROCESSING");
        assertThat(postings.outcome("transfer-rollback").reservationStatus()).isEqualTo("RESERVED");

        assertThat(postings.postInternalTransfer(command).status()).isEqualTo("SUCCESS");
        postings.postInternalTransfer(command);
        assertMoney("payer", "400.00", "0.00");
        assertMoney("payee", "120.00", "0.00");
        assertThat(flowCount("transfer-rollback")).isEqualTo(2);
    }

    @Test
    void replayStillChecksOriginalAmountAndTransferPayee() {
        transfer("transfer-replay", "100.00");
        Command command = outgoingCommand("transfer-replay", "100.00", "payee");
        postings.reserve(command);
        assertError(ErrorCode.REQUEST_CONFLICT,
                () -> postings.postInternalTransfer(outgoingCommand("transfer-replay", "100.00", "payer")));
        postings.postInternalTransfer(command);
        assertError(ErrorCode.REQUEST_CONFLICT,
                () -> postings.postInternalTransfer(outgoingCommand("transfer-replay", "101.00", "payee")));
        assertMoney("payer", "400.00", "0.00");
        assertMoney("payee", "120.00", "0.00");
        assertThat(flowCount("transfer-replay")).isEqualTo(2);
    }

    @Test
    void concurrentReservationsCannotBothPassDailyLimitFromAnOldSnapshot() throws Exception {
        jdbc.update("INSERT INTO risk_rule (rule_id, rule_type, deposit_single_limit, outgoing_single_limit, "
                        + "outgoing_daily_limit, enabled, rule_version) VALUES (?, 'LIMIT_POLICY', 50000.00, "
                        + "1000.00, 150.00, 1, 1)", "daily-policy");
        withdrawal("limit-one", "100.00");
        withdrawal("limit-two", "100.00");
        var commands = List.of(outgoingCommand("limit-one", "100.00", null),
                outgoingCommand("limit-two", "100.00", null));
        List<Outcome> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> postings.reserve(commands.get(0)));
            var second = executor.submit(() -> postings.reserve(commands.get(1)));
            results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertThat(results).extracting(Outcome::status).containsExactlyInAnyOrder("PROCESSING", "REJECTED");
        assertThat(results).filteredOn(outcome -> outcome.status().equals("REJECTED"))
                .singleElement().satisfies(outcome -> assertThat(outcome.reasonCode()).isEqualTo("DAILY_LIMIT_EXCEEDED"));
        assertMoney("payer", "500.00", "100.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_reservation WHERE status = 'RESERVED'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void lateOutgoingCompletionBelongsToOriginalAcceptanceDay() {
        jdbc.update("INSERT INTO risk_rule (rule_id, rule_type, deposit_single_limit, outgoing_single_limit, "
                        + "outgoing_daily_limit, enabled, rule_version) VALUES (?, 'LIMIT_POLICY', 50000.00, "
                        + "1000.00, 100.00, 1, 1)", "daily-policy");
        withdrawal("yesterday", "100.00");
        postings.reserve(outgoingCommand("yesterday", "100.00", null));
        java.time.LocalDate yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1);
        jdbc.update("UPDATE `transaction` SET accepted_at = ? WHERE trade_no = 'yesterday'",
                yesterday.atTime(12, 0));
        postings.completeWithdrawal(outgoingCommand("yesterday", "100.00", null));
        withdrawal("today", "100.00");
        assertThat(postings.reserve(outgoingCommand("today", "100.00", null)).status()).isEqualTo("PROCESSING");
        assertMoney("payer", "400.00", "100.00");
    }

    @Test
    void localFailureCannotBeUsedToTurnUnknownDepositIntoFailure() {
        deposit("deposit-unknown-failure", "100.00");
        postings.acceptDeposit(depositCommand("deposit-unknown-failure", "100.00"));
        postings.depositResult(result("deposit-unknown-failure", "100.00", "UNKNOWN", null, "CHANNEL_TIMEOUT"));
        assertThatThrownBy(() -> postings.recordFailure(new Failure("deposit-unknown-failure", "FAILED",
                "CHANNEL_TIMEOUT", "LOCAL_FAILURE", "timeout-is-not-failure"))).isInstanceOf(BizException.class);
        assertThat(tradeStatus("deposit-unknown-failure")).isEqualTo("UNKNOWN");
        assertMoney("payer", "500.00", "0.00");
    }

    @Test
    void lowercaseTimeoutReasonCannotReleaseAReservedWithdrawal() {
        withdrawal("lowercase-timeout", "100.00");
        postings.reserve(outgoingCommand("lowercase-timeout", "100.00", null));
        Release timeout = new Release("lowercase-timeout", "payer", "100.00", "CNY", "channel_timeout",
                "LOCAL_FAILURE", "timeout-observation");
        assertError(ErrorCode.INVALID_ARGUMENT, () -> postings.release(timeout));
        assertMoney("payer", "500.00", "100.00");
        assertThat(tradeStatus("lowercase-timeout")).isEqualTo("PROCESSING");
        assertThat(postings.outcome("lowercase-timeout").reservationStatus()).isEqualTo("RESERVED");
        assertThat(flowCount("lowercase-timeout")).isZero();
    }

    @Test
    void aNonemptyEvidenceIdCannotMakeAChannelTimeoutAnExplicitDepositFailure() {
        deposit("timeout-not-failure", "100.00");
        postings.acceptDeposit(depositCommand("timeout-not-failure", "100.00"));
        assertError(ErrorCode.INVALID_ARGUMENT, () -> postings.depositResult(
                result("timeout-not-failure", "100.00", "FAILED", "timeout-observation", "CHANNEL_TIMEOUT")));
        assertMoney("payer", "500.00", "0.00");
        assertThat(tradeStatus("timeout-not-failure")).isEqualTo("PROCESSING");
        assertThat(flowCount("timeout-not-failure")).isZero();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM `transaction` WHERE trade_no = ?",
                java.time.LocalDateTime.class, "timeout-not-failure")).isNull();
    }

    @Test
    void internalSnapshotsCheckIdentityAndDoNotExposePayeeBalances() throws Exception {
        String body = "{\"identityId\":\"identity-1\",\"businessType\":\"INTERNAL_TRANSFER\","
                + "\"payerAccountId\":\"payer\",\"payeeAccountId\":\"payee\"}";
        mvc.perform(post("/internal/v1/accounts/snapshots").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/accounts/snapshots")
                        .header("X-Internal-Token", "s2-test-service-token-32-bytes-long")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.accountId").value("payer"))
                .andExpect(jsonPath("$.data.account.bookBalance").value("500.00"))
                .andExpect(jsonPath("$.data.account.reservedAmount").value("0.00"))
                .andExpect(jsonPath("$.data.account.availableBalance").value("500.00"))
                .andExpect(jsonPath("$.data.payee.accountId").value("payee"))
                .andExpect(jsonPath("$.data.payee.currency").value("CNY"))
                .andExpect(jsonPath("$.data.payee.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.payee.length()").value(3))
                .andExpect(jsonPath("$.data.payee.bookBalance").doesNotExist())
                .andExpect(jsonPath("$.data.payee.reservedAmount").doesNotExist())
                .andExpect(jsonPath("$.data.payee.availableBalance").doesNotExist());
        mvc.perform(post("/internal/v1/accounts/snapshots")
                        .header("X-Internal-Token", "s2-test-service-token-32-bytes-long")
                        .contentType("application/json").content(body.replace("identity-1", "identity-2")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void riskRejectionRequiresMatchingPersistentEvidenceAndPersistsTerminalState() {
        deposit("risk-rejected", "100.00");
        deposit("other-risk-trade", "100.00");
        riskEvent("risk-evidence", "risk-rejected", "REJECT");
        Failure rejection = new Failure("risk-rejected", "REJECTED", "ACCOUNT_STATUS_INVALID",
                "RISK_EVENT", "risk-evidence");
        assertError(ErrorCode.INVALID_ARGUMENT, () -> postings.recordFailure(new Failure("risk-rejected", "REJECTED",
                "ACCOUNT_STATUS_INVALID", "LOCAL_FAILURE", "risk-evidence")));
        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.recordFailure(new Failure("other-risk-trade", "REJECTED",
                "ACCOUNT_STATUS_INVALID", "RISK_EVENT", "risk-evidence")));
        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.recordFailure(new Failure("risk-rejected", "REJECTED",
                "SINGLE_LIMIT_EXCEEDED", "RISK_EVENT", "risk-evidence")));
        assertThat(tradeStatus("risk-rejected")).isEqualTo("ACCEPTED");
        assertThat(tradeStatus("other-risk-trade")).isEqualTo("ACCEPTED");

        assertThat(postings.recordFailure(rejection).status()).isEqualTo("REJECTED");
        assertThat(postings.recordFailure(rejection).reasonCode()).isEqualTo("ACCOUNT_STATUS_INVALID");
        assertThat(tradeStatus("risk-rejected")).isEqualTo("REJECTED");
        assertThat(flowCount("risk-rejected")).isZero();
        assertMoney("payer", "500.00", "0.00");
    }

    @Test
    void acceptedRiskPassCannotBeReusedAsARejectionAndDepositFailureCannotBypassChannelResult() {
        deposit("risk-pass", "100.00");
        riskEvent("pass-evidence", "risk-pass", "PASS");
        assertError(ErrorCode.REQUEST_CONFLICT, () -> postings.recordFailure(new Failure("risk-pass", "REJECTED",
                "ACCOUNT_STATUS_INVALID", "RISK_EVENT", "pass-evidence")));
        assertError(ErrorCode.INVALID_ARGUMENT, () -> postings.recordFailure(new Failure("risk-pass", "FAILED",
                "CHANNEL_DECLINED", "LOCAL_FAILURE", "fake-channel-evidence")));
        assertThat(tradeStatus("risk-pass")).isEqualTo("ACCEPTED");
        assertThat(flowCount("risk-pass")).isZero();
        assertMoney("payer", "500.00", "0.00");
    }

    @Test
    void httpBoundariesRejectForgedIdentityAndMissingInternalToken() throws Exception {
        mvc.perform(get("/api/v1/accounts").header("X-Identity-Id", "identity-1")
                        .header("X-Customer-Id", "customer-1"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/internal/v1/postings/deposit-acceptances").contentType("application/json")
                        .content("{\"tradeNo\":\"not-executed\",\"accountId\":\"payer\",\"amount\":\"1.00\",\"currency\":\"CNY\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/accounts/payee/balance")
                        .principal(new Actor("identity-1", "customer-1", Set.of(), Set.of())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void httpTypeErrorsReturn400InsteadOfCoercingMoneyOrReturning500() throws Exception {
        mvc.perform(post("/internal/v1/postings/deposit-acceptances")
                        .header("X-Internal-Token", "s2-test-service-token-32-bytes-long")
                        .contentType("application/json")
                        .content("{\"tradeNo\":\"not-executed\",\"accountId\":\"payer\",\"amount\":100.00,\"currency\":\"CNY\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(post("/internal/v1/postings/deposit-results")
                        .header("X-Internal-Token", "s2-test-service-token-32-bytes-long")
                        .contentType("application/json")
                        .content("{\"tradeNo\":\"not-executed\",\"accountId\":\"payer\",\"amount\":\"1.00\","
                                + "\"currency\":\"CNY\",\"result\":\"UNKNOWN\",\"channelRequestId\":\"CHDnot-executed\","
                                + "\"observedAt\":\"not-a-time\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(get("/api/v1/accounts").param("page", "not-a-number")
                        .principal(new Actor("identity-1", "customer-1", Set.of(), Set.of())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    private void customer(String customerId, String identityId) {
        jdbc.update("INSERT INTO customer (customer_id, identity_id, name, real_name_status, status) "
                + "VALUES (?, ?, 'Test customer', 'VERIFIED', 'NORMAL')", customerId, identityId);
    }

    private void account(String accountId, String customerId, String bookBalance) {
        jdbc.update("INSERT INTO account (account_id, customer_id, currency, status, book_balance, reserved_amount) "
                + "VALUES (?, ?, 'CNY', 'ACTIVE', ?, 0.00)", accountId, customerId, new BigDecimal(bookBalance));
    }

    private void deposit(String tradeNo, String amount) { trade(tradeNo, "DEPOSIT", amount); }
    private void withdrawal(String tradeNo, String amount) { trade(tradeNo, "WITHDRAWAL", amount); }

    private void transfer(String tradeNo, String amount) {
        trade(tradeNo, "INTERNAL_TRANSFER", amount);
        jdbc.update("INSERT INTO transfer_order (transfer_id, trade_no, transfer_type, payer_account_id, "
                        + "payee_account_id, amount, currency, clearing_status) "
                        + "VALUES (?, ?, 'INTERNAL', 'payer', 'payee', ?, 'CNY', 'NOT_APPLICABLE')",
                "transfer-" + UUID.randomUUID().toString().substring(0, 12), tradeNo, new BigDecimal(amount));
    }

    private void riskEvent(String eventId, String tradeNo, String result) {
        jdbc.update("INSERT INTO risk_rule (rule_id, rule_type, enabled, rule_version) "
                + "VALUES (?, 'ACCOUNT_STATUS', 1, 1)", "rule-" + eventId);
        jdbc.update("INSERT INTO risk_event (event_id, trade_no, rule_id, rule_version, operator_identity_id, "
                        + "reason_code, result) VALUES (?, ?, ?, 1, 'SYSTEM', 'ACCOUNT_STATUS', ?)",
                eventId, tradeNo, "rule-" + eventId, result);
    }

    private void trade(String tradeNo, String type, String amount) {
        jdbc.update("INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash, "
                        + "account_id, amount, currency, status) VALUES (?, 'identity-1', ?, ?, ?, 'payer', ?, 'CNY', 'ACCEPTED')",
                tradeNo, type, UUID.randomUUID().toString(), "0".repeat(64), new BigDecimal(amount));
    }

    private Command depositCommand(String tradeNo, String amount) {
        return new Command(tradeNo, null, null, "payer", amount, "CNY");
    }

    private Command outgoingCommand(String tradeNo, String amount, String payee) {
        return new Command(tradeNo, "payer", payee, null, amount, "CNY");
    }

    private DepositResult result(String tradeNo, String amount, String status, String evidence, String reason) {
        return new DepositResult(tradeNo, "payer", amount, "CNY", status, "CHD" + tradeNo, evidence,
                OffsetDateTime.now(java.time.ZoneOffset.ofHours(8)), reason);
    }

    private List<Outcome> concurrent(int threads, Callable<Outcome> command) throws Exception {
        List<Callable<Outcome>> calls = new ArrayList<>();
        for (int index = 0; index < threads; index++) calls.add(command);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var results = new ArrayList<Outcome>();
            for (var future : executor.invokeAll(calls, 15, TimeUnit.SECONDS)) {
                results.add(future.get(1, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private long flowCount(String tradeNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transaction_flow WHERE trade_no = ?", Long.class, tradeNo);
    }

    private String tradeStatus(String tradeNo) {
        return jdbc.queryForObject("SELECT status FROM `transaction` WHERE trade_no = ?", String.class, tradeNo);
    }

    private void assertMoney(String accountId, String bookBalance, String reservedAmount) {
        var values = jdbc.queryForMap("SELECT book_balance, reserved_amount FROM account WHERE account_id = ?", accountId);
        BigDecimal book = (BigDecimal) values.get("book_balance");
        BigDecimal reserved = (BigDecimal) values.get("reserved_amount");
        assertThat(book).isEqualByComparingTo(bookBalance);
        assertThat(reserved).isEqualByComparingTo(reservedAmount);
        assertThat(book).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(reserved).isBetween(BigDecimal.ZERO, book);
    }

    private void assertError(ErrorCode code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }
}
