package com.digitalbank.transaction.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.dto.TransactionDtos.TradeView;
import com.digitalbank.transaction.dto.TransactionDtos.TransferRequest;
import com.digitalbank.transaction.security.Actor;
import com.digitalbank.transaction.security.TransactionAccess;
import com.digitalbank.transaction.service.ExternalTransferService;
import com.digitalbank.transaction.service.TransferService;
import com.digitalbank.transaction.support.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外转账受理：本行（payeeAccountId）与跨行（externalBankCode + externalPayeeRef）共用
 * 一个入口按字段分派；付款账户优先 payerAccountId，兼容旧字段 accountId。
 */
@RestController
public class TransferController {
    private final TransferService internalService;
    private final ExternalTransferService externalService;
    private final TransactionAccess access;

    public TransferController(TransferService internalService, ExternalTransferService externalService,
            TransactionAccess access) {
        this.internalService = internalService;
        this.externalService = externalService;
        this.access = access;
    }

    @PostMapping("/api/v1/transfers")
    public ApiResponse<TradeView> transfer(@RequestBody TransferRequest body, HttpServletRequest request) {
        Actor actor = access.requireActor(request);
        String requestId = RequestIds.resolve(body.requestId(), request.getHeader("X-Request-Id"));
        String traceId = (String) request.getAttribute(TraceIds.MDC_KEY);
        String payerAccountId = resolvePayer(body);
        boolean internal = isFilled(body.payeeAccountId());
        boolean external = isFilled(body.externalBankCode());
        if (internal && external) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "本行收款与跨行收款不能同时提供");
        }
        if (!internal && !external) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "本行转账需 payeeAccountId，跨行转账需 externalBankCode");
        }
        TradeView view = internal
                ? internalService.transfer(actor, payerAccountId, body.payeeAccountId(), body.amount(),
                        body.currency(), body.purpose(), requestId, traceId)
                : externalService.transfer(actor, payerAccountId, body.externalBankCode(),
                        body.externalPayeeRef(), body.amount(), body.currency(), body.purpose(),
                        requestId, traceId);
        return ApiResponse.success(view, traceId);
    }

    /** 付款账户取 payerAccountId，回落旧字段 accountId；两者同传且不同值时返回参数错误。 */
    private String resolvePayer(TransferRequest body) {
        String payerAccountId = body.payerAccountId();
        if (payerAccountId == null || payerAccountId.isBlank()) return body.accountId();
        if (isFilled(body.accountId()) && !body.accountId().equals(payerAccountId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "accountId 与 payerAccountId 不一致");
        }
        return payerAccountId;
    }

    private static boolean isFilled(String value) {
        return value != null && !value.isBlank();
    }
}
