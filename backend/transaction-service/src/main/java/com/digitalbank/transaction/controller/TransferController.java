package com.digitalbank.transaction.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.dto.TransactionDtos.TradeView;
import com.digitalbank.transaction.dto.TransactionDtos.TransferRequest;
import com.digitalbank.transaction.security.Actor;
import com.digitalbank.transaction.security.TransactionAccess;
import com.digitalbank.transaction.service.TransferService;
import com.digitalbank.transaction.support.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 对外本行转账受理：先认证，再校验请求格式与对象权限，认领原单并落转账单后编排。 */
@RestController
public class TransferController {
    private final TransferService service;
    private final TransactionAccess access;

    public TransferController(TransferService service, TransactionAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/api/v1/transfers")
    public ApiResponse<TradeView> transfer(@RequestBody TransferRequest body, HttpServletRequest request) {
        Actor actor = access.requireActor(request);
        String requestId = RequestIds.resolve(body.requestId(), request.getHeader("X-Request-Id"));
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        TradeView view = service.transfer(actor, body.accountId(), body.payeeAccountId(), body.amount(),
                body.currency(), body.purpose(), requestId, traceId);
        return ApiResponse.success(view, traceId);
    }
}
