package com.digitalbank.user.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.AccessService;
import com.digitalbank.common.auth.AuthTokens;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.user.dto.LoginRequest;
import com.digitalbank.user.dto.LoginResponse;
import com.digitalbank.user.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final SessionService sessionService;
    private final AccessService accessService;

    public AuthController(SessionService sessionService, AccessService accessService) {
        this.sessionService = sessionService;
        this.accessService = accessService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        LoginResponse result = sessionService.login(body);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result, trace(request)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SessionIdentity>> currentUser(HttpServletRequest request) {
        SessionIdentity identity = accessService.currentIdentity(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(identity, trace(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        String token = AuthTokens.requireBearer(
                Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)));
        sessionService.logout(token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(null, trace(request)));
    }

    private String trace(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIds.MDC_KEY);
    }
}
