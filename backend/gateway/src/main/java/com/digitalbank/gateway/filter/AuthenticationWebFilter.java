package com.digitalbank.gateway.filter;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.AuthPaths;
import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.InternalServiceToken;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.gateway.service.GatewaySessionVerifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationWebFilter implements WebFilter {
    private static final List<String> UNTRUSTED_IDENTITY_HEADERS = List.of(
            "X-Customer-Id", "X-Identity-Id", "X-User-Id", "X-User-Roles",
            "X-Authenticated-User", InternalServiceToken.HEADER
    );

    private final GatewaySessionVerifier verifier;
    private final ObjectMapper objectMapper;

    public AuthenticationWebFilter(GatewaySessionVerifier verifier, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/internal/")) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }

        // 客户端身份头一律删除。下游通过 Bearer 令牌独立核验身份。
        ServerWebExchange sanitized = exchange.mutate()
                .request(request -> request.headers(headers ->
                        UNTRUSTED_IDENTITY_HEADERS.forEach(headers::remove)))
                .build();
        String method = exchange.getRequest().getMethod().name();
        if (AuthPaths.isPublic(method, path)) {
            return chain.filter(sanitized);
        }

        String traceId = exchange.getRequest().getHeaders().getFirst(TraceIds.HEADER);
        return Mono.defer(() -> {
            String token = AuthTokens.requireBearer(
                    exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION));
            return verifier.verify(token, traceId);
        })
                .flatMap(identity -> chain.filter(sanitized))
                .onErrorResume(BizException.class, error -> writeFailure(sanitized, error, traceId));
    }

    private Mono<Void> writeFailure(ServerWebExchange exchange, BizException error, String traceId) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(error.getErrorCode().status()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        ApiResponse<Void> body = ApiResponse.failure(
                error.getErrorCode(), error.getMessage(), traceId);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException ex) {
            return Mono.error(ex);
        }
    }
}
