package com.digitalbank.account.controller;

import com.digitalbank.account.dto.PostingDtos.Command;
import com.digitalbank.account.dto.PostingDtos.DepositResult;
import com.digitalbank.account.dto.PostingDtos.Failure;
import com.digitalbank.account.dto.PostingDtos.Outcome;
import com.digitalbank.account.dto.PostingDtos.Release;
import com.digitalbank.account.security.InternalAccess;
import com.digitalbank.account.service.PostingService;
import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/postings")
public class PostingController {
    private final PostingService service;
    private final InternalAccess access;

    public PostingController(PostingService service, InternalAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/deposit-acceptances")
    public ApiResponse<Outcome> accept(@RequestBody Command command, HttpServletRequest request) {
        access.require(request);
        return response(service.acceptDeposit(command), request);
    }

    @PostMapping("/deposit-completions")
    public ApiResponse<Outcome> deposit(@RequestBody DepositResult command, HttpServletRequest request) {
        access.require(request);
        return response(service.completeDeposit(command), request);
    }

    @PostMapping("/deposit-results")
    public ApiResponse<Outcome> result(@RequestBody DepositResult command, HttpServletRequest request) {
        access.require(request);
        return response(service.depositResult(command), request);
    }

    @PostMapping("/reserve")
    public ApiResponse<Outcome> reserve(@RequestBody Command command, HttpServletRequest request) {
        access.require(request);
        return response(service.reserve(command), request);
    }

    @PostMapping("/withdrawal-completions")
    public ApiResponse<Outcome> withdrawal(@RequestBody Command command, HttpServletRequest request) {
        access.require(request);
        return response(service.completeWithdrawal(command), request);
    }

    @PostMapping("/internal-transfers")
    public ApiResponse<Outcome> transfer(@RequestBody Command command, HttpServletRequest request) {
        access.require(request);
        return response(service.postInternalTransfer(command), request);
    }

    @PostMapping("/releases")
    public ApiResponse<Outcome> release(@RequestBody Release command, HttpServletRequest request) {
        access.require(request);
        return response(service.release(command), request);
    }

    @PostMapping("/failures")
    public ApiResponse<Outcome> failure(@RequestBody Failure command, HttpServletRequest request) {
        access.require(request);
        return response(service.recordFailure(command), request);
    }

    @GetMapping("/{tradeNo}")
    public ApiResponse<Outcome> outcome(@PathVariable String tradeNo, HttpServletRequest request) {
        access.require(request);
        return response(service.outcome(tradeNo), request);
    }

    private ApiResponse<Outcome> response(Outcome outcome, HttpServletRequest request) {
        return ApiResponse.success(outcome, (String) request.getAttribute(TraceIds.MDC_KEY));
    }
}
