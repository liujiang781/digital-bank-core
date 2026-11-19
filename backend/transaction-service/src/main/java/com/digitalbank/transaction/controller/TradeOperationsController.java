package com.digitalbank.transaction.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.dto.TransactionDtos.ManualActionResult;
import com.digitalbank.transaction.dto.TransactionDtos.ReasonRequest;
import com.digitalbank.transaction.security.Actor;
import com.digitalbank.transaction.security.TransactionAccess;
import com.digitalbank.transaction.service.TradeOperationsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营交易接口：人工核对、恢复重试（UC-TR-08/09）。
 * 待处理清单复用 GET /api/v1/transactions 的 status 筛选，不新增契约外路径。
 * 身份来自服务端核验的会话，角色与账户范围由服务层强制校验，管理员一律拒绝。
 */
@RestController
public class TradeOperationsController {
    private final TradeOperationsService service;
    private final TransactionAccess access;

    public TradeOperationsController(TradeOperationsService service, TransactionAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/api/v1/transactions/{tradeNo}/reconcile")
    public ApiResponse<ManualActionResult> reconcile(@PathVariable String tradeNo,
            @RequestBody ReasonRequest body, HttpServletRequest request) {
        Actor actor = access.requireActor(request);
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        return ApiResponse.success(service.reconcile(actor, tradeNo, body, traceId), traceId);
    }

    @PostMapping("/api/v1/transactions/{tradeNo}/recover")
    public ApiResponse<ManualActionResult> recover(@PathVariable String tradeNo,
            @RequestBody ReasonRequest body, HttpServletRequest request) {
        Actor actor = access.requireActor(request);
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        return ApiResponse.success(service.recover(actor, tradeNo, body, traceId), traceId);
    }
}
