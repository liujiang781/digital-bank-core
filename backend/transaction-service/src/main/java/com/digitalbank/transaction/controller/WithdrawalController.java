package com.digitalbank.transaction.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.dto.TransactionDtos.TradeView;
import com.digitalbank.transaction.dto.TransactionDtos.WithdrawalRequest;
import com.digitalbank.transaction.security.Actor;
import com.digitalbank.transaction.security.TransactionAccess;
import com.digitalbank.transaction.service.WithdrawalService;
import com.digitalbank.transaction.support.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 对外取款受理：先认证，再校验请求格式与对象权限，最后按三元组认领原单并编排。 */
@RestController
public class WithdrawalController {
    private final WithdrawalService service;
    private final TransactionAccess access;

    public WithdrawalController(WithdrawalService service, TransactionAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/api/v1/withdrawals")
    public ApiResponse<TradeView> withdraw(@RequestBody WithdrawalRequest body, HttpServletRequest request) {
        Actor actor = access.requireActor(request);
        String requestId = RequestIds.resolve(body.requestId(), request.getHeader("X-Request-Id"));
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        TradeView view = service.withdraw(actor, body.accountId(), body.amount(), body.currency(),
                body.purpose(), requestId, traceId);
        return ApiResponse.success(view, traceId);
    }
}
