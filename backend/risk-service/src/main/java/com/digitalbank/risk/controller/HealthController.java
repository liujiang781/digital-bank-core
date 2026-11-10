package com.digitalbank.risk.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/risk/health")
    public ApiResponse<Map<String, String>> health(HttpServletRequest request) {
        return ApiResponse.success(Map.of("service", "risk-service", "status", "UP"),
                (String) request.getAttribute(TraceIds.MDC_KEY));
    }
}
