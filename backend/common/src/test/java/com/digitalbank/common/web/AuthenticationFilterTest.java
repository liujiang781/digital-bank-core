package com.digitalbank.common.web;

import com.digitalbank.common.auth.AccessService;
import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationFilterTest {
    private static final String TOKEN = "a".repeat(43);
    private static final SessionIdentity IDENTITY = new SessionIdentity("customer-1", "identity-1");

    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/accounts/snapshots", "/internal/v1/postings/deposit-acceptances",
            "/internal/v1/auth/session", "/internal/v1/risk/checks"})
    void internalRequestsReachModuleServiceAuthenticationWithoutUserToken(String path) throws Exception {
        var filter = new AuthenticationFilter((token, trace) -> {
            throw new AssertionError("Internal calls must not verify a user session");
        }, new ObjectMapper());
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader("X-Internal-Token", "module-service-token");
        var reached = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (incoming, response) -> {
            assertThat(((HttpServletRequest) incoming).getHeader("X-Internal-Token"))
                    .isEqualTo("module-service-token");
            reached.set(true);
        });
        assertThat(reached).isTrue();
    }

    @Test
    void internalPathsAlsoWorkUnderServletContext() throws Exception {
        var filter = new AuthenticationFilter((token, trace) -> {
            throw new AssertionError("Internal calls must not verify a user session");
        }, new ObjectMapper());
        var request = new MockHttpServletRequest("POST", "/bank/internal/v1/accounts/snapshots");
        request.setContextPath("/bank");
        var reached = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (incoming, response) -> reached.set(true));
        assertThat(reached).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/accounts", "/internal/v10/accounts", "/internal/v1evil/accounts"})
    void missingUserTokenCannotBypassAuthentication(String path) throws Exception {
        var filter = new AuthenticationFilter((token, trace) -> IDENTITY, new ObjectMapper());
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-Internal-Token", "module-service-token");
        request.addHeader("X-Identity-Id", "forged-identity");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Unauthenticated request reached business code");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void verifiedSessionSuppliesPrincipalAndPreservesS1IdentityAttribute() throws Exception {
        var filter = new AuthenticationFilter((token, trace) -> {
            assertThat(token).isEqualTo(TOKEN);
            return IDENTITY;
        }, new ObjectMapper());
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("X-Identity-Id", "forged-identity");
        request.setUserPrincipal(() -> "old-identity");
        request.addUserRole("OPERATIONS");
        var reached = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (incoming, response) -> {
            var authenticated = (HttpServletRequest) incoming;
            assertThat(authenticated.getUserPrincipal().getName()).isEqualTo("identity-1");
            assertThat(authenticated.getRemoteUser()).isEqualTo("identity-1");
            assertThat(authenticated.isUserInRole("OPERATIONS")).isFalse();
            assertThat(new AccessService().currentIdentity(authenticated)).isEqualTo(IDENTITY);
            reached.set(true);
        });
        assertThat(reached).isTrue();
    }

    @Test
    void rejectedSessionCannotSupplyPrincipal() throws Exception {
        var filter = new AuthenticationFilter((token, trace) -> {
            throw AuthTokens.unauthenticated();
        }, new ObjectMapper());
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Rejected session reached business code");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void downstreamBusinessErrorsAreNotRewrittenAsAuthenticationErrors() {
        var filter = new AuthenticationFilter((token, trace) -> IDENTITY, new ObjectMapper());
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        BizException error = AuthTokens.unauthenticated();
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (incoming, response) -> { throw error; })).isSameAs(error);
    }
}
