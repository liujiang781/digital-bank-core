package com.digitalbank.transaction.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.dto.TransactionDtos.TradeDetailView;
import com.digitalbank.transaction.dto.TransactionDtos.TradePage;
import com.digitalbank.transaction.security.TransactionAccess;
import com.digitalbank.transaction.service.TradeQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 对外交易查询：权限校验在前，内部查询接口不暴露给前端。 */
@RestController
public class TradeQueryController {
    private final TradeQueryService service;
    private final TransactionAccess access;

    public TradeQueryController(TradeQueryService service, TransactionAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/api/v1/transactions/{tradeNo}")
    public ApiResponse<TradeDetailView> detail(@PathVariable String tradeNo, HttpServletRequest request) {
        String actorIdentityId = access.requireActor(request).identityId();
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        return ApiResponse.success(service.detail(actorIdentityId, tradeNo, traceId), traceId);
    }

    @GetMapping("/api/v1/transactions")
    public ApiResponse<TradePage> list(@RequestParam(required = false) String businessType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String actorIdentityId = access.requireActor(request).identityId();
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        return ApiResponse.success(
                service.list(actorIdentityId, businessType, status, page, pageSize, traceId), traceId);
    }
}
