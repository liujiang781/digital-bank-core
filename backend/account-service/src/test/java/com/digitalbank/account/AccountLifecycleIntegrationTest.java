package com.digitalbank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalbank.account.dto.AccountDtos.*;
import com.digitalbank.account.security.Actor;
import com.digitalbank.account.service.AccountService;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@org.springframework.context.annotation.Import(SessionVerifierTestConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Sql(scripts = {"/schema-test.sql", "/db/s2-incremental.sql"})
class AccountLifecycleIntegrationTest {
    private static final Actor CUSTOMER = new Actor("identity-c1", "customer-c1", Set.of(), Set.of());
    private static final Actor OTHER = new Actor("identity-c2", "customer-c2", Set.of(), Set.of());
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void customers() {
        jdbc.update("INSERT INTO customer (customer_id, identity_id, name, real_name_status, status) "
                + "VALUES ('customer-c1', 'identity-c1', '客户一', 'VERIFIED', 'NORMAL')");
        jdbc.update("INSERT INTO customer (customer_id, identity_id, name, real_name_status, status) "
                + "VALUES ('customer-c2', 'identity-c2', '客户二', 'VERIFIED', 'NORMAL')");
    }

    @Test
    void repeatedOpenReturnsOneOriginalAccountAndOneSuccessfulTransaction() {
        String requestId = requestId();
        AccountView first = accounts.open(CUSTOMER, new OpenRequest("CNY", requestId), requestId);
        AccountView replay = accounts.open(CUSTOMER, new OpenRequest("CNY", requestId), requestId);

        assertThat(replay).isEqualTo(first);
        assertThat(first.bookBalance()).isEqualTo("0.00");
        assertThat(first.reservedAmount()).isEqualTo("0.00");
        assertThat(first.availableBalance()).isEqualTo("0.00");
        assertThat(count("account")).isEqualTo(1);
        assertThat(count("account_command")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `transaction` "
                + "WHERE identity_id = 'identity-c1' AND business_type = 'ACCOUNT_OPEN' AND status = 'SUCCESS'", Long.class))
                .isEqualTo(1);
        assertThat(count("transaction_flow")).isZero();
    }

    @Test
    void concurrentOpenRequestsCannotExceedThreeIncludingFrozenAccounts() throws Exception {
        AccountView first = open(CUSTOMER);
        Actor staff = staff("operations-1", "OPERATIONS", first.accountId());
        accounts.freeze(staff, first.accountId(), new FreezeRequest("运营限制", null), requestId());

        int workers = 8;
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        List<Future<ErrorCode>> futures = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(workers)) {
            for (int index = 0; index < workers; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    try {
                        open(CUSTOMER);
                        return null;
                    } catch (BizException ex) {
                        return ex.getErrorCode();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long successes = 0;
            for (var future : futures) {
                ErrorCode result = future.get(30, TimeUnit.SECONDS);
                if (result == null) successes++;
                else assertThat(result).isEqualTo(ErrorCode.ACCOUNT_LIMIT_REACHED);
            }
            assertThat(successes).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE customer_id = ? AND status <> 'CLOSED'",
                Long.class, CUSTOMER.customerId())).isEqualTo(3);
        assertThat(accounts.get(CUSTOMER, first.accountId()).status()).isEqualTo("FROZEN");
    }

    @Test
    void concurrentRetriesOfOneOpenRequestCreateOneAccount() throws Exception {
        String requestId = requestId();
        var start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    return accounts.open(CUSTOMER, new OpenRequest("CNY", requestId), requestId).accountId();
                }));
            }
            start.countDown();
            Set<String> returned = new java.util.HashSet<>();
            for (var future : futures) returned.add(future.get(30, TimeUnit.SECONDS));
            assertThat(returned).hasSize(1);
        }
        assertThat(count("account")).isEqualTo(1);
        assertThat(count("account_command")).isEqualTo(1);
        assertThat(count("transaction")).isEqualTo(1);
    }

    @Test
    void allRestrictionsMustBeResolvedAndReplayingAnOldFreezeDoesNotRefreeze() {
        String accountId = open(CUSTOMER).accountId();
        Actor ops = staff("operations-1", "OPERATIONS", accountId);
        Actor risk = staff("risk-1", "RISK", accountId);
        String firstRequest = requestId();
        FreezeResult first = accounts.freeze(ops, accountId, new FreezeRequest("运营核查", null), firstRequest);
        FreezeResult second = accounts.freeze(risk, accountId, new FreezeRequest("风险限制", null), requestId());
        List<Long> all = second.restrictions().stream().map(RestrictionView::restrictionId).toList();

        assertThat(first.restrictions()).hasSize(1);
        assertThat(second.restrictions()).hasSize(2);
        assertThat(accounts.get(CUSTOMER, accountId).restrictions()).containsExactlyElementsOf(second.restrictions());
        assertError(() -> accounts.unfreeze(risk, accountId,
                new UnfreezeRequest("风险解除", "风险复核通过", all, null), requestId()), ErrorCode.FORBIDDEN);
        assertError(() -> accounts.unfreeze(ops, accountId,
                new UnfreezeRequest("仅解除一项", "运营核查完成", List.of(all.getFirst()), null), requestId()),
                ErrorCode.ACCOUNT_STATUS_INVALID);
        assertThat(accounts.get(CUSTOMER, accountId).status()).isEqualTo("FROZEN");

        String unfreezeRequest = requestId();
        var body = new UnfreezeRequest("全部限制均解除", "运营和风控分别核对通过", all, null);
        assertThat(accounts.unfreeze(ops, accountId, body, unfreezeRequest).status()).isEqualTo("ACTIVE");
        assertThat(accounts.unfreeze(ops, accountId, body, unfreezeRequest).status()).isEqualTo("ACTIVE");
        assertThat(accounts.freeze(ops, accountId, new FreezeRequest("运营核查", null), firstRequest)).isEqualTo(first);
        assertThat(accounts.get(CUSTOMER, accountId).status()).isEqualTo("ACTIVE");
        assertThat(accounts.get(CUSTOMER, accountId).restrictions()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_status_log WHERE new_status = 'FROZEN'", Long.class))
                .isEqualTo(2);
        assertError(() -> accounts.freeze(ops, accountId, new FreezeRequest("不同内容", null), firstRequest),
                ErrorCode.REQUEST_CONFLICT);
        assertThat(count("transaction_flow")).isZero();
    }

    @Test
    void authorizationIsRecheckedBeforeReturningAnIdempotentResult() {
        String accountId = open(CUSTOMER).accountId();
        Actor allowed = staff("operations-1", "OPERATIONS", accountId);
        String requestId = requestId();
        var request = new FreezeRequest("运营核查", null);
        accounts.freeze(allowed, accountId, request, requestId);
        Actor revoked = new Actor("operations-1", null, Set.of("OPERATIONS"), Set.of());
        assertError(() -> accounts.freeze(revoked, accountId, request, requestId), ErrorCode.FORBIDDEN);
        assertError(() -> accounts.get(OTHER, accountId), ErrorCode.ACCOUNT_NOT_FOUND);
        assertError(() -> accounts.balance(OTHER, accountId), ErrorCode.ACCOUNT_NOT_FOUND);
        assertError(() -> accounts.fundEntries(OTHER, accountId, 1, 20, null, null), ErrorCode.ACCOUNT_NOT_FOUND);
        assertError(() -> accounts.freeze(CUSTOMER, accountId, request, requestId()), ErrorCode.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "PROCESSING", "UNKNOWN", "RECOVERING"})
    void zeroBalancesDoNotPermitClosingAnAccountWithAnUnresolvedDeposit(String status) {
        String accountId = open(CUSTOMER).accountId();
        String tradeNo = trade(accountId, CUSTOMER.identityId(), "DEPOSIT", status);
        String requestId = requestId();

        assertError(() -> accounts.close(CUSTOMER, accountId, requestId), ErrorCode.ACCOUNT_NOT_CLOSABLE);
        assertThat(accounts.get(CUSTOMER, accountId).status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_command WHERE command_type = 'ACCOUNT_CLOSE'", Long.class))
                .isZero();
        jdbc.update("UPDATE `transaction` SET status = 'REJECTED' WHERE trade_no = ?", tradeNo);
        assertThat(accounts.close(CUSTOMER, accountId, requestId).status()).isEqualTo("CLOSED");
    }

    @Test
    void closingAlsoChecksPendingIncomingTransfers() {
        String payee = open(CUSTOMER).accountId();
        String payer = open(OTHER).accountId();
        String tradeNo = trade(payer, OTHER.identityId(), "INTERNAL_TRANSFER", "ACCEPTED");
        jdbc.update("INSERT INTO transfer_order (transfer_id, trade_no, transfer_type, payer_account_id, payee_account_id, amount, currency) "
                        + "VALUES (?, ?, 'INTERNAL', ?, ?, 1.00, 'CNY')", id(), tradeNo, payer, payee);
        assertError(() -> accounts.close(CUSTOMER, payee, requestId()), ErrorCode.ACCOUNT_NOT_CLOSABLE);
        assertThat(accounts.get(CUSTOMER, payee).status()).isEqualTo("ACTIVE");
    }

    @Test
    void closedAccountsRemainQueryableAndNoLongerCountTowardTheOpenLimit() {
        String first = open(CUSTOMER).accountId();
        open(CUSTOMER);
        open(CUSTOMER);
        assertThat(accounts.close(CUSTOMER, first, requestId()).status()).isEqualTo("CLOSED");
        assertThat(open(CUSTOMER).status()).isEqualTo("ACTIVE");
        assertThat(accounts.get(CUSTOMER, first).status()).isEqualTo("CLOSED");
        assertThat(accounts.operations(CUSTOMER, first, 1, 20, null, null).items())
                .extracting(OperationView::action).contains("ACCOUNT_OPEN", "ACCOUNT_CLOSE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE status <> 'CLOSED'", Long.class)).isEqualTo(3);
    }

    @Test
    void latestEnabledPolicyControlsPersonalLimitsAndInvalidUpdatesLeaveThemUnchanged() {
        String accountId = open(CUSTOMER).accountId();
        policy("older-policy", 1, "100.00", "1000.00");
        policy("latest-policy", 2, "200.00", "2000.00");
        var accepted = new LimitsRequest("150.00", "1500.00", null);
        assertThat(accounts.limits(CUSTOMER, accountId, accepted, requestId()).personalSingleLimit()).isEqualTo("150.00");
        assertError(() -> accounts.limits(CUSTOMER, accountId,
                new LimitsRequest("250.00", "1500.00", null), requestId()), ErrorCode.INVALID_ARGUMENT);
        assertError(() -> accounts.limits(CUSTOMER, accountId,
                new LimitsRequest("200.00", "100.00", null), requestId()), ErrorCode.INVALID_ARGUMENT);
        assertThat(accounts.get(CUSTOMER, accountId).personalSingleLimit()).isEqualTo("150.00");
        assertThat(accounts.get(CUSTOMER, accountId).personalDailyLimit()).isEqualTo("1500.00");
    }

    @Test
    void balanceIsDerivedFromTheSameStoredSnapshotAndListRespectsScopes() {
        String own = open(CUSTOMER).accountId();
        String other = open(OTHER).accountId();
        jdbc.update("UPDATE account SET book_balance = 100.00, reserved_amount = 35.25 WHERE account_id = ?", own);
        BalanceView balance = accounts.balance(CUSTOMER, own);
        assertThat(balance.bookBalance()).isEqualTo("100.00");
        assertThat(balance.reservedAmount()).isEqualTo("35.25");
        assertThat(balance.availableBalance()).isEqualTo("64.75");
        assertThat(accounts.list(CUSTOMER, 1, 20).items()).extracting(AccountView::accountId).containsExactly(own);
        Actor scoped = staff("operations-1", "OPERATIONS", other);
        assertThat(accounts.list(scoped, 1, 20).items()).extracting(AccountView::accountId).containsExactly(other);
        Actor wildcard = staff("operations-2", "OPERATIONS", "*");
        assertThat(accounts.list(wildcard, 1, 20).items()).isEmpty();
        assertError(() -> accounts.list(CUSTOMER, 0, 20), ErrorCode.INVALID_ARGUMENT);
        assertError(() -> accounts.list(CUSTOMER, 1, 101), ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void auditFailureRollsBackTheStatusLogAndCommandClaim() {
        String accountId = open(CUSTOMER).accountId();
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT reject_test_freeze CHECK (`action` <> 'ACCOUNT_FREEZE')");
        Actor ops = staff("operations-1", "OPERATIONS", accountId);
        assertThatThrownBy(() -> accounts.freeze(ops, accountId, new FreezeRequest("运营核查", null), requestId()))
                .isInstanceOf(DataAccessException.class);
        assertThat(accounts.get(CUSTOMER, accountId).status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_status_log WHERE new_status = 'FROZEN'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_command WHERE command_type = 'ACCOUNT_FREEZE'", Long.class)).isZero();
    }

    @Test
    void publicEndpointsRejectUnverifiedIdentityHeadersAndInvalidRequestContracts() throws Exception {
        mvc.perform(get("/api/v1/accounts").header("X-Customer-Id", CUSTOMER.customerId())
                        .header("X-Identity-Id", CUSTOMER.identityId()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        String requestId = requestId();
        mvc.perform(post("/api/v1/accounts")
                        .header("Authorization", SessionVerifierTestConfiguration.LIFECYCLE_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "lifecycle-contract")
                        .content("{\"currency\":\"CNY\",\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCEEDED"))
                .andExpect(jsonPath("$.traceId").value("lifecycle-contract"))
                .andExpect(jsonPath("$.data.bookBalance").value("0.00"));
        String accountId = accounts.list(CUSTOMER, 1, 20).items().getFirst().accountId();

        mvc.perform(post("/api/v1/accounts/{id}/limits", accountId)
                        .header("Authorization", SessionVerifierTestConfiguration.LIFECYCLE_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"personalSingleLimit\":100.00,"
                                + "\"personalDailyLimit\":\"1000.00\",\"requestId\":\"" + requestId() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(post("/api/v1/accounts")
                        .header("Authorization", SessionVerifierTestConfiguration.LIFECYCLE_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", requestId())
                        .content("{\"currency\":\"CNY\",\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertThat(count("account")).isEqualTo(1);
    }

    private AccountView open(Actor actor) {
        String requestId = requestId();
        return accounts.open(actor, new OpenRequest("CNY", requestId), requestId);
    }

    private Actor staff(String identity, String role, String accountId) {
        return new Actor(identity, null, Set.of(role), Set.of(accountId));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
    }

    private String trade(String accountId, String identityId, String businessType, String status) {
        String tradeNo = id();
        jdbc.update("INSERT INTO `transaction` (trade_no, identity_id, business_type, request_id, request_hash, account_id, amount, currency, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1.00, 'CNY', ?)",
                tradeNo, identityId, businessType, requestId(), "a".repeat(64), accountId, status);
        return tradeNo;
    }

    private void policy(String ruleId, int version, String single, String daily) {
        jdbc.update("INSERT INTO risk_rule (rule_id, rule_type, deposit_single_limit, outgoing_single_limit, "
                        + "outgoing_daily_limit, enabled, rule_version) VALUES (?, 'LIMIT_POLICY', 50000.00, ?, ?, 1, ?)",
                ruleId, new BigDecimal(single), new BigDecimal(daily), version);
    }

    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    private static String requestId() { return UUID.randomUUID().toString(); }
    private static String id() { return requestId().replace("-", ""); }
}
