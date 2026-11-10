package com.digitalbank.user.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.InternalServiceToken;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.user.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalAuthController {
    private final SessionService sessionService;
    private final InternalServiceToken serviceToken;

    public InternalAuthController(SessionService sessionService, InternalServiceToken serviceToken) {
        this.sessionService = sessionService;
        this.serviceToken = serviceToken;
    }

    @GetMapping("/internal/v1/auth/session")
    public ResponseEntity<ApiResponse<SessionIdentity>> verify(HttpServletRequest request) {
        // 先验证调用方服务身份，再检查用户令牌；此接口不通过网关开放。
        serviceToken.requireMatch(request.getHeader(InternalServiceToken.HEADER));
        String token = AuthTokens.requireBearer(
                Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)));
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        SessionIdentity identity = sessionService.verify(token, traceId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(identity, traceId));
    }
}
