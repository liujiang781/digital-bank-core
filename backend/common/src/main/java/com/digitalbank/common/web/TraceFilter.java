package com.digitalbank.common.web;

import com.digitalbank.common.trace.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String traceId = TraceIds.normalize(request.getHeader(TraceIds.HEADER));
        request.setAttribute(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        String previous = MDC.get(TraceIds.MDC_KEY);
        MDC.put(TraceIds.MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove(TraceIds.MDC_KEY);
            else MDC.put(TraceIds.MDC_KEY, previous);
        }
    }
}
