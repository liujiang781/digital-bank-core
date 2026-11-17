package com.digitalbank.gateway.filter;

import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.InternalServiceToken;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.gateway.service.GatewaySessionVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationWebFilterTest {
    private static final String TOKEN = "a".repeat(43);

    private AuthenticationWebFilter filter(boolean reject) {
        var verifier = new GatewaySessionVerifier(WebClient.create(), new InternalServiceToken("test".repeat(8))) {
            @Override public Mono<SessionIdentity> verify(String token, String trace) {
                assertThat(token).isEqualTo(TOKEN);
                return reject ? Mono.error(AuthTokens.unauthenticated())
                        : Mono.just(new SessionIdentity("customer-1", "identity-1"));
            }
        };
        return new AuthenticationWebFilter(verifier, new ObjectMapper());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/auth/session", "/internal/v1/accounts/snapshots"})
    void blocksInternalRoutes(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        filter(false).filter(exchange, next -> Mono.error(new AssertionError("Internal route leaked"))).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void stripsClientIdentityAndServiceHeadersOnPublicLogin() {
        var request = MockServerHttpRequest.post("/api/v1/auth/login")
                .header("X-Customer-Id", "forged").header("X-Identity-Id", "forged")
                .header("X-User-Id", "forged").header("X-User-Roles", "OPERATIONS")
                .header("X-Authenticated-User", "forged").header(InternalServiceToken.HEADER, "forged")
                .header("X-Roles", "OPERATIONS").header("X-Account-Scope", "account-1");
        var exchange = MockServerWebExchange.from(request);
        var seen = new AtomicReference<ServerWebExchange>();
        filter(false).filter(exchange, next -> { seen.set(next); return Mono.empty(); }).block();
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getRequest().getHeaders().keySet()).doesNotContain(
                "X-Customer-Id", "X-Identity-Id", "X-User-Id", "X-User-Roles",
                "X-Authenticated-User", "X-Roles", "X-Account-Scope", InternalServiceToken.HEADER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/accounts", "/api/v1/auth/me", "/api/v1/auth/login/extra"})
    void missingTokenNeverReachesProtectedRoutes(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        filter(false).filter(exchange, next -> Mono.error(new AssertionError("Anonymous request leaked"))).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("UNAUTHENTICATED");
    }

    @Test
    void validSessionPreservesBearerAndRemovesForgedIdentity() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN).header("X-Identity-Id", "forged"));
        var seen = new AtomicReference<ServerWebExchange>();
        filter(false).filter(exchange, next -> { seen.set(next); return Mono.empty(); }).block();
        assertThat(seen.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + TOKEN);
        assertThat(seen.get().getRequest().getHeaders()).doesNotContainKey("X-Identity-Id");
    }

    @Test
    void rejectedSessionStopsRequest() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN).header("X-Trace-Id", "s1-gateway-rejected"));
        filter(true).filter(exchange, next -> Mono.error(new AssertionError("Rejected session leaked"))).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("s1-gateway-rejected");
    }

    @Test
    void duplicateAuthorizationHeadersAreRejected() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN, "Bearer " + TOKEN));
        filter(false).filter(exchange, next -> Mono.error(new AssertionError("Duplicate header leaked"))).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }
}
