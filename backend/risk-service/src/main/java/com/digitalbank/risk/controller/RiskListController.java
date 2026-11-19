package com.digitalbank.risk.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.risk.dto.RiskDtos.ListChangeRequest;
import com.digitalbank.risk.dto.RiskDtos.ListEntryView;
import com.digitalbank.risk.dto.RiskDtos.ListPage;
import com.digitalbank.risk.security.RiskAccess;
import com.digitalbank.risk.service.RiskListService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 风险名单维护（UC-RK-02，待评审项）：查询与提交变更；审批走 /api/v1/risk/changes/{id}/approve。 */
@RestController
public class RiskListController {
    private final RiskListService service;
    private final RiskAccess access;

    public RiskListController(RiskListService service, RiskAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/api/v1/risk/lists")
    public ApiResponse<ListPage> list(@RequestParam(required = false) String listType,
            @RequestParam(required = false) String subjectType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        SessionIdentity identity = access.requireRiskWriter(request);
        return ApiResponse.success(service.list(identity, listType, subjectType, page, pageSize), null);
    }

    @PostMapping("/api/v1/risk/lists/changes")
    public ApiResponse<ListEntryView> submit(@RequestBody ListChangeRequest body,
            HttpServletRequest request) {
        SessionIdentity identity = access.requireRiskWriter(request);
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        return ApiResponse.success(service.submit(identity, body, traceId), traceId);
    }
}
