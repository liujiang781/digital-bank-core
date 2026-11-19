package com.digitalbank.risk.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.risk.security.RiskAccess;
import com.digitalbank.risk.service.FreezeReleaseService;
import com.digitalbank.risk.service.FreezeReleaseService.Approval;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FreezeReleaseController {
    private final RiskAccess access;
    private final FreezeReleaseService service;

    public FreezeReleaseController(RiskAccess access, FreezeReleaseService service) {
        this.access = access;
        this.service = service;
    }

    @PostMapping("/api/v1/risk/accounts/{accountId}/restrictions/{restrictionId}/release-approval")
    public ApiResponse<Approval> approve(@PathVariable String accountId, @PathVariable long restrictionId,
            @RequestBody ReleaseRequest body, HttpServletRequest request) {
        var actor = access.requireRiskWriter(request);
        Approval result = service.approve(actor, accountId, restrictionId, body.basis());
        return ApiResponse.success(result, (String) request.getAttribute(TraceIds.MDC_KEY));
    }

    public record ReleaseRequest(String basis) { }
}
