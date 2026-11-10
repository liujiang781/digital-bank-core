package com.digitalbank.gateway.filter;

import com.digitalbank.common.trace.TraceIds;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceWebFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String supplied = exchange.getRequest().getHeaders().getFirst(TraceIds.HEADER);
        String traceId = TraceIds.normalize(supplied);
        ServerWebExchange traced = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(TraceIds.HEADER, traceId)))
                .build();
        traced.getResponse().getHeaders().set(TraceIds.HEADER, traceId);
        return chain.filter(traced);
    }
}
