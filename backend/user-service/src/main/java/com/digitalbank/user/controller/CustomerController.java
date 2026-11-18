package com.digitalbank.user.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.AccessService;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.user.dto.UserDtos.*;
import com.digitalbank.user.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class CustomerController {
    private final CustomerService customers;
    private final AccessService access;

    public CustomerController(CustomerService customers, AccessService access) {
        this.customers = customers;
        this.access = access;
    }

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<ApiResponse<RegisterResult>> register(@Valid @RequestBody RegisterRequest body,
                                                               HttpServletRequest request) {
        return ok(customers.register(body), request);
    }

    @GetMapping("/api/v1/customers/me")
    public ResponseEntity<ApiResponse<Profile>> me(HttpServletRequest request) {
        return ok(customers.me(access.currentIdentity(request)), request);
    }

    @PutMapping("/api/v1/customers/me")
    public ResponseEntity<ApiResponse<Profile>> update(@Valid @RequestBody ProfileRequest body,
                                                     HttpServletRequest request) {
        return ok(customers.update(access.currentIdentity(request), body), request);
    }

    @GetMapping("/api/v1/customers")
    public ResponseEntity<ApiResponse<Page<CustomerSummary>>> list(
            @RequestParam(required = false) String status, @RequestParam String reason,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return ok(customers.customers(access.currentIdentity(request), status, reason, page, pageSize), request);
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(data, (String) request.getAttribute(TraceIds.MDC_KEY)));
    }
}
