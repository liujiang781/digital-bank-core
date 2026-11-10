package com.digitalbank.transaction.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.dto.TransactionDtos.DepositRequest;
import com.digitalbank.transaction.dto.TransactionDtos.TradeView;
import com.digitalbank.transaction.security.Actor;
import com.digitalbank.transaction.security.TransactionAccess;
import com.digitalbank.transaction.service.DepositService;
import com.digitalbank.transaction.support.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 对外存款受理：先认证，再校验请求格式与对象权限，最后按三元组认领原单。 */
@RestController
public class DepositController {
    private final DepositService service;
    private final TransactionAccess access;

    public DepositController(DepositService service, TransactionAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/api/v1/deposits")
    public ApiResponse<TradeView> deposit(@RequestBody DepositRequest body, HttpServletRequest request) {
        Actor actor = access.requireActor(request);
        String requestId = RequestIds.resolve(body.requestId(), request.getHeader("X-Request-Id"));
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        TradeView view = service.deposit(actor, body.accountId(), body.amount(), body.purpose(),
                requestId, traceId);
        return ApiResponse.success(view, traceId);
    }
}
