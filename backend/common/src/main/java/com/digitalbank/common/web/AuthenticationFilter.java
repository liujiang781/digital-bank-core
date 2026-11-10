package com.digitalbank.common.web;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.AccessService;
import com.digitalbank.common.auth.AuthPaths;
import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.auth.SessionVerifier;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.common.trace.TraceIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class AuthenticationFilter extends OncePerRequestFilter {
    private final SessionVerifier verifier;
    private final ObjectMapper objectMapper;

    public AuthenticationFilter(SessionVerifier verifier, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 内部核验接口由 Controller 单独验证服务密钥，不能再递归核验自身。
        return AuthPaths.isPublic(request.getMethod(), path)
                || "/internal/v1/auth/session".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            String token = AuthTokens.requireBearer(
                    Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)));
            String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
            SessionIdentity identity = verifier.verify(token, traceId);
            request.setAttribute(AccessService.IDENTITY_ATTRIBUTE, identity);
        } catch (BizException ex) {
            writeFailure(request, response, ex);
            return;
        }
        // 业务异常由 ControllerAdvice 处理，避免吞掉业务层错误。
        chain.doFilter(request, response);
    }

    private void writeFailure(HttpServletRequest request, HttpServletResponse response,
            BizException ex) throws IOException {
        response.setStatus(ex.getErrorCode().status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        ApiResponse<Void> body = ApiResponse.failure(ex.getErrorCode(), ex.getMessage(), traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
