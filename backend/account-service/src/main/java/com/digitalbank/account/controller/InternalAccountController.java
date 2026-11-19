package com.digitalbank.account.controller;

import com.digitalbank.account.dto.PostingDtos.Snapshot;
import com.digitalbank.account.dto.PostingDtos.SnapshotRequest;
import com.digitalbank.account.security.InternalAccess;
import com.digitalbank.account.service.PostingService;
import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/accounts")
public class InternalAccountController {
    private final PostingService service;
    private final InternalAccess access;

    public InternalAccountController(PostingService service, InternalAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/snapshots")
    public ApiResponse<Snapshot> snapshot(@RequestBody SnapshotRequest body, HttpServletRequest request) {
        access.require(request);
        return ApiResponse.success(service.snapshot(body), (String) request.getAttribute(TraceIds.MDC_KEY));
    }
}
