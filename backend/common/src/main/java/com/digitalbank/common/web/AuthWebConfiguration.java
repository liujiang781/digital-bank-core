package com.digitalbank.common.web;

import com.digitalbank.common.auth.AccessService;
import com.digitalbank.common.auth.InternalServiceToken;
import com.digitalbank.common.auth.RemoteSessionVerifier;
import com.digitalbank.common.auth.SessionVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuthWebConfiguration {
    @Bean
    public InternalServiceToken internalServiceToken(
            @Value("${auth.internal-token:${BANK_INTERNAL_TOKEN:}}") String token) {
        return new InternalServiceToken(token);
    }

    @Bean
    @ConditionalOnProperty(name = "auth.verification-mode", havingValue = "remote", matchIfMissing = true)
    public SessionVerifier remoteSessionVerifier(
            @Value("${auth.user-service-url:${USER_SERVICE_URL:http://localhost:8081}}") String userServiceUrl,
            InternalServiceToken serviceToken) {
        return new RemoteSessionVerifier(userServiceUrl, serviceToken);
    }

    @Bean
    public AccessService accessService() {
        return new AccessService();
    }

    @Bean
    public AuthenticationFilter authenticationFilter(SessionVerifier verifier, ObjectMapper mapper) {
        return new AuthenticationFilter(verifier, mapper);
    }
}
