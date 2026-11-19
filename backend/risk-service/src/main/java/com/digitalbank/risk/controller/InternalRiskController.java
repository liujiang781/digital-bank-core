package com.digitalbank.risk.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.risk.dto.RiskDtos.CheckRequest;
import com.digitalbank.risk.dto.RiskDtos.Decision;
import com.digitalbank.risk.security.InternalAccess;
import com.digitalbank.risk.service.RiskCheckService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 风控内部求值端点：仅交易服务调用，X-Internal-Token 保护，网关不路由 /internal/v1/**。 */
@RestController
@RequestMapping("/internal/v1/risk")
public class InternalRiskController {
    private final RiskCheckService service;
    private final InternalAccess access;

    public InternalRiskController(RiskCheckService service, InternalAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/checks")
    public ApiResponse<Decision> check(@RequestBody CheckRequest body, HttpServletRequest request) {
        access.require(request);
        Decision decision = service.check(body);
        return ApiResponse.success(decision, (String) request.getAttribute(TraceIds.MDC_KEY));
    }
}
