package com.digitalbank.account.security;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.security.Principal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityBoundaryTest {
    private JdbcTemplate jdbc;
    private AccountAccess access;

    @BeforeEach
    void database() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:access-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE customer (customer_id VARCHAR(32), identity_id VARCHAR(64), "
                + "status VARCHAR(16), real_name_status VARCHAR(16))");
        jdbc.update("INSERT INTO customer VALUES (?, ?, 'NORMAL', 'VERIFIED')", "customer-1", "identity-1");
        access = new AccountAccess(jdbc);
    }

    @Test
    void identityAndRoleHeadersCannotAuthenticateARequest() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Identity-Id", "identity-1");
        request.addHeader("X-Customer-Id", "customer-1");
        request.addHeader("X-Roles", "OPERATIONS");
        assertError(ErrorCode.UNAUTHENTICATED, () -> access.requireActor(request));
    }

    @Test
    void genericPrincipalResolvesIdentityAndCannotGainUnscopedStaffAccess() {
        var request = new MockHttpServletRequest();
        request.setUserPrincipal((Principal) () -> "identity-1");
        request.addUserRole("OPERATIONS");
        request.addHeader("X-Account-Scope", "account-1");
        Actor actor = access.requireActor(request);
        assertThat(actor.customerId()).isEqualTo("customer-1");
        assertThat(actor.identityId()).isEqualTo("identity-1");
        assertThat(actor.accountScope()).isEmpty();
        assertError(ErrorCode.FORBIDDEN, () -> access.requireOperator(actor, "account-1", false));
        assertError(ErrorCode.ACCOUNT_NOT_FOUND, () -> access.requireOwner(actor, "someone-else"));
    }

    @Test
    void customerAuthenticationRequiresNormalVerifiedRecord() {
        var request = new MockHttpServletRequest();
        request.setUserPrincipal((Principal) () -> "identity-1");
        jdbc.update("UPDATE customer SET real_name_status = 'UNVERIFIED'");
        assertError(ErrorCode.UNAUTHENTICATED, () -> access.requireActor(request));
        jdbc.update("UPDATE customer SET real_name_status = 'VERIFIED', status = 'FROZEN'");
        assertError(ErrorCode.UNAUTHENTICATED, () -> access.requireActor(request));
    }

    @Test
    void staffMustHaveBothApplicableRoleAndExactObjectScope() {
        var risk = new Actor("risk-1", null, Set.of("RISK"), Set.of("account-1"));
        assertThatCode(() -> access.requireOperator(risk, "account-1", true)).doesNotThrowAnyException();
        assertError(ErrorCode.FORBIDDEN, () -> access.requireOperator(risk, "account-1", false));
        assertError(ErrorCode.FORBIDDEN, () -> access.requireOperator(risk, "account-2", true));
        var wildcard = new Actor("operations-1", null, Set.of("OPERATIONS"), Set.of("*"));
        assertError(ErrorCode.FORBIDDEN, () -> access.requireOperator(wildcard, "account-1", false));
    }

    @Test
    void authenticatedActorScopeCannotBeChangedAfterConstruction() {
        var roles = new HashSet<>(Set.of("CUSTOMER"));
        var scope = new HashSet<>(Set.of("account-1"));
        var actor = new Actor("identity-1", "customer-1", roles, scope);
        roles.add("OPERATIONS");
        scope.add("account-2");
        assertThat(actor.roles()).containsExactly("CUSTOMER");
        assertThat(actor.accountScope()).containsExactly("account-1");
        assertThatThrownBy(() -> actor.accountScope().add("account-3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void internalCommandsRequireConfiguredExactServiceToken() {
        var request = new MockHttpServletRequest();
        var enabled = new InternalAccess("test-service-secret-at-least-32-bytes");
        assertError(ErrorCode.UNAUTHENTICATED, () -> enabled.require(request));
        request.addHeader("X-Internal-Token", "wrong-secret");
        assertError(ErrorCode.UNAUTHENTICATED, () -> enabled.require(request));
        request.removeHeader("X-Internal-Token");
        request.addHeader("X-Internal-Token", "test-service-secret-at-least-32-bytes");
        assertThatCode(() -> enabled.require(request)).doesNotThrowAnyException();
        assertError(ErrorCode.UNAUTHENTICATED, () -> new InternalAccess("").require(request));
        assertError(ErrorCode.UNAUTHENTICATED, () -> new InternalAccess(" ").require(request));
        assertThatThrownBy(() -> new InternalAccess("short-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertError(ErrorCode code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }
}
