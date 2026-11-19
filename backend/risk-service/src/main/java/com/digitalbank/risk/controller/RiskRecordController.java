package com.digitalbank.risk.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.SessionIdentity;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.risk.dto.RiskDtos.RiskRecordPage;
import com.digitalbank.risk.security.RiskAccess;
import com.digitalbank.risk.service.RiskRecordService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 风控对外查询：GET /api/v1/risk/events，会话角色 + 账户范围授权，带查询原因审计。 */
@RestController
public class RiskRecordController {
    private final RiskRecordService service;
    private final RiskAccess access;

    public RiskRecordController(RiskRecordService service, RiskAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/api/v1/risk/events")
    public ApiResponse<RiskRecordPage> events(@RequestParam(required = false) String tradeNo,
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String reason,
            HttpServletRequest request) {
        SessionIdentity identity = access.requireRiskReader(request);
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        return ApiResponse.success(service.query(identity, tradeNo, ruleId, reasonCode, result,
                startTime, endTime, page, pageSize, reason, traceId), traceId);
    }
}
