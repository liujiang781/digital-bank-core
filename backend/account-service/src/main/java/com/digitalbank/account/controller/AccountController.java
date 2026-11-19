package com.digitalbank.account.controller;

import com.digitalbank.account.dto.AccountDtos.*;
import com.digitalbank.account.security.AccountAccess;
import com.digitalbank.account.service.AccountService;
import com.digitalbank.account.support.RequestIds;
import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService accounts;
    private final AccountAccess access;

    public AccountController(AccountService accounts, AccountAccess access) {
        this.accounts = accounts;
        this.access = access;
    }

    @PostMapping
    public ApiResponse<AccountView> open(@RequestBody OpenRequest body,
            @RequestHeader(value = "X-Request-Id", required = false) String header, HttpServletRequest request) {
        var actor = access.requireActor(request);
        return ok(accounts.open(actor, body, RequestIds.resolve(body.requestId(), header)), request);
    }

    @GetMapping
    public ApiResponse<Page<AccountView>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, HttpServletRequest request) {
        return ok(accounts.list(access.requireActor(request), page, pageSize), request);
    }

    @GetMapping("/{accountId}")
    public ApiResponse<AccountView> get(@PathVariable String accountId, HttpServletRequest request) {
        return ok(accounts.get(access.requireActor(request), accountId), request);
    }

    @GetMapping("/{accountId}/balance")
    public ApiResponse<BalanceView> balance(@PathVariable String accountId, HttpServletRequest request) {
        return ok(accounts.balance(access.requireActor(request), accountId), request);
    }

    @PostMapping("/{accountId}/freeze")
    public ApiResponse<FreezeResult> freeze(@PathVariable String accountId, @RequestBody FreezeRequest body,
            @RequestHeader(value = "X-Request-Id", required = false) String header, HttpServletRequest request) {
        var actor = access.requireActor(request);
        return ok(accounts.freeze(actor, accountId, body, RequestIds.resolve(body.requestId(), header)), request);
    }

    @PostMapping("/{accountId}/unfreeze")
    public ApiResponse<StatusResult> unfreeze(@PathVariable String accountId, @RequestBody UnfreezeRequest body,
            @RequestHeader(value = "X-Request-Id", required = false) String header, HttpServletRequest request) {
        var actor = access.requireActor(request);
        return ok(accounts.unfreeze(actor, accountId, body, RequestIds.resolve(body.requestId(), header)), request);
    }

    @PostMapping("/{accountId}/limits")
    public ApiResponse<LimitsResult> limits(@PathVariable String accountId, @RequestBody LimitsRequest body,
            @RequestHeader(value = "X-Request-Id", required = false) String header, HttpServletRequest request) {
        var actor = access.requireActor(request);
        return ok(accounts.limits(actor, accountId, body, RequestIds.resolve(body.requestId(), header)), request);
    }

    @PostMapping("/{accountId}/close")
    public ApiResponse<StatusResult> close(@PathVariable String accountId, @RequestBody CloseRequest body,
            @RequestHeader(value = "X-Request-Id", required = false) String header, HttpServletRequest request) {
        var actor = access.requireActor(request);
        return ok(accounts.close(actor, accountId, RequestIds.resolve(body.requestId(), header)), request);
    }

    @GetMapping("/{accountId}/operations")
    public ApiResponse<Page<OperationView>> operations(@PathVariable String accountId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime,
            HttpServletRequest request) {
        return ok(accounts.operations(access.requireActor(request), accountId, page, pageSize, startTime, endTime), request);
    }

    @GetMapping("/{accountId}/fund-entries")
    public ApiResponse<Page<FundEntryView>> entries(@PathVariable String accountId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime,
            HttpServletRequest request) {
        return ok(accounts.fundEntries(access.requireActor(request), accountId, page, pageSize, startTime, endTime), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.success(data, (String) request.getAttribute(TraceIds.MDC_KEY));
    }
}
