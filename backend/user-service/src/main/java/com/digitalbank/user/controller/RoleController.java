package com.digitalbank.user.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.auth.AccessService;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.user.dto.UserDtos.*;
import com.digitalbank.user.service.RoleManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {
    private final RoleManagementService roles;
    private final AccessService access;

    public RoleController(RoleManagementService roles, AccessService access) {
        this.roles = roles;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Authorization>> get(@RequestParam String identityId,
                                                         HttpServletRequest request) {
        return ok(roles.get(access.currentIdentity(request), identityId), request);
    }

    @PutMapping("/{roleId}")
    public ResponseEntity<ApiResponse<Authorization>> replace(@PathVariable String roleId,
            @Valid @RequestBody RoleRequest body, HttpServletRequest request) {
        return ok(roles.replace(access.currentIdentity(request), roleId, body), request);
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(data, (String) request.getAttribute(TraceIds.MDC_KEY)));
    }
}
