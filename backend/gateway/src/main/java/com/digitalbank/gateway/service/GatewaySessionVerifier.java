package com.digitalbank.gateway.service;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.InternalServiceToken;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.common.trace.TraceIds;
import java.time.Duration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class GatewaySessionVerifier {
    private final WebClient client;
    private final InternalServiceToken serviceToken;

    public GatewaySessionVerifier(WebClient authWebClient, InternalServiceToken serviceToken) {
        this.client = authWebClient;
        this.serviceToken = serviceToken;
    }

    public Mono<SessionIdentity> verify(String token, String traceId) {
        // WebFlux 事件循环中不调用 block()，也不缓存会话，确保退出立即生效。
        return Mono.defer(() -> client.get()
                .uri("/internal/v1/auth/session")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(InternalServiceToken.HEADER, serviceToken.configuredValue())
                .header(TraceIds.HEADER, traceId)
                .retrieve()
                .onStatus(status -> status.value() == 401,
                        response -> Mono.error(AuthTokens.unauthenticated()))
                .onStatus(status -> status.isError(),
                        response -> Mono.error(unavailable()))
                .bodyToMono(new ParameterizedTypeReference<ApiResponse<SessionIdentity>>() { }))
                .timeout(Duration.ofSeconds(3))
                .switchIfEmpty(Mono.error(unavailable()))
                .flatMap(response -> {
                    SessionIdentity identity = response.data();
                    if (!"SUCCEEDED".equals(response.code()) || identity == null || !identity.isValid()) {
                        return Mono.error(unavailable());
                    }
                    return Mono.just(identity);
                })
                .onErrorMap(error -> !(error instanceof BizException), error -> unavailable());
    }

    private BizException unavailable() {
        return new BizException(ErrorCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
    }
}
