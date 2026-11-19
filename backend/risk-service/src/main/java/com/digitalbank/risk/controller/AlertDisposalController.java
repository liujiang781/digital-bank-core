package com.digitalbank.risk.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.risk.dto.RiskDtos.AlertDisposalView;
import com.digitalbank.risk.dto.RiskDtos.AlertDisposeRequest;
import com.digitalbank.risk.security.RiskAccess;
import com.digitalbank.risk.service.AlertDisposalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 风险告警处置（UC-RK-03）：POST /api/v1/risk/alerts/{eventId}/dispose，仅风控专员。 */
@RestController
public class AlertDisposalController {
    private final AlertDisposalService service;
    private final RiskAccess access;

    public AlertDisposalController(AlertDisposalService service, RiskAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/api/v1/risk/alerts/{eventId}/dispose")
    public ApiResponse<AlertDisposalView> dispose(@PathVariable String eventId,
            @RequestBody AlertDisposeRequest body, HttpServletRequest request) {
        SessionIdentity identity = access.requireRiskWriter(request);
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        return ApiResponse.success(service.dispose(identity, eventId, body, traceId), traceId);
    }
}
