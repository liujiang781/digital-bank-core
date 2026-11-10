package com.digitalbank.account.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/account/health")
    public ApiResponse<Map<String, String>> health(HttpServletRequest request) {
        return ApiResponse.success(Map.of("service", "account-service", "status", "UP"),
                (String) request.getAttribute(TraceIds.MDC_KEY));
    }
}

