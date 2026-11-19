package com.digitalbank.risk.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.risk.dto.RiskDtos.ApprovalRequest;
import com.digitalbank.risk.dto.RiskDtos.ChangePage;
import com.digitalbank.risk.dto.RiskDtos.RuleChangeRequest;
import com.digitalbank.risk.dto.RiskDtos.RuleChangeView;
import com.digitalbank.risk.dto.RiskDtos.RuleView;
import com.digitalbank.risk.security.RiskAccess;
import com.digitalbank.risk.service.RiskListService;
import com.digitalbank.risk.service.RuleChangeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 风控规则维护与审批（UC-RK-01）：查询、提交变更、审批应用，全部带审计。 */
@RestController
public class RuleChangeController {
    private final RuleChangeService service;
    private final RiskListService listService;
    private final RiskAccess access;

    public RuleChangeController(RuleChangeService service, RiskListService listService, RiskAccess access) {
        this.service = service;
        this.listService = listService;
        this.access = access;
    }

    @GetMapping("/api/v1/risk/rules")
    public ApiResponse<List<RuleView>> rules(HttpServletRequest request) {
        access.requireRuleReader(request);
        return ApiResponse.success(service.rules(), null);
    }

    @PutMapping("/api/v1/risk/rules/{ruleId}")
    public ApiResponse<RuleChangeView> submit(@PathVariable String ruleId,
            @RequestBody RuleChangeRequest body, HttpServletRequest request) {
        SessionIdentity identity = access.requireRiskWriter(request);
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        return ApiResponse.success(service.submit(identity, ruleId, body, traceId), traceId);
    }

    @GetMapping("/api/v1/risk/changes")
    public ApiResponse<ChangePage> changes(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        SessionIdentity identity = access.requireRiskWriter(request);
        return ApiResponse.success(service.changes(identity, status, page, pageSize), null);
    }

    /** 审批变更单：RULE 走规则服务，LIST 走名单服务；审批人不得为提交人。 */
    @PostMapping("/api/v1/risk/changes/{changeId}/approve")
    public ApiResponse<?> approve(@PathVariable String changeId,
            @RequestBody ApprovalRequest body, HttpServletRequest request) {
        SessionIdentity identity = access.requireRiskWriter(request);
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        if ("LIST".equals(service.changeType(changeId))) {
            return ApiResponse.success(listService.approve(identity, changeId, body, traceId), traceId);
        }
        return ApiResponse.success(service.approve(identity, changeId, body, traceId), traceId);
    }
}
