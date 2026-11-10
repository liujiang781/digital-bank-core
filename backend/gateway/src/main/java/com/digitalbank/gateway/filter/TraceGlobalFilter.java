package com.digitalbank.gateway.filter;

import com.digitalbank.common.trace.TraceIds;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TraceGlobalFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceIds.normalize(exchange.getRequest().getHeaders().getFirst(TraceIds.HEADER));
        ServerWebExchange traced = exchange.mutate().request(request ->
                request.headers(headers -> headers.set(TraceIds.HEADER, traceId))).build();
        traced.getResponse().getHeaders().set(TraceIds.HEADER, traceId);
        return chain.filter(traced);
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
