package com.digitalbank.account;

import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.auth.SessionVerifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replace only the remote S1 call; retain the real authentication filter and S2 authorization. */
@TestConfiguration(proxyBeanMethods = false)
class SessionVerifierTestConfiguration {
    static final String LIFECYCLE_AUTHORIZATION = "Bearer " + "a".repeat(43);
    static final String POSTING_AUTHORIZATION = "Bearer " + "b".repeat(43);

    @Bean
    @Primary
    SessionVerifier testSessionVerifier() {
        return (token, traceId) -> {
            if (token.equals("a".repeat(43))) return new SessionIdentity("customer-c1", "identity-c1");
            if (token.equals("b".repeat(43))) return new SessionIdentity("customer-1", "identity-1");
            throw AuthTokens.unauthenticated();
        };
    }
}
