package com.digitalbank.user.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;

public final class UserDtos {
    private UserDtos() { }

    public record RegisterRequest(
            @NotBlank @Size(min = 2, max = 100) String realName,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,64}") String identityId,
            @NotBlank @Size(min = 6, max = 72) String password,
            @NotBlank String requestId) {
        @Override public String toString() { return "RegisterRequest[redacted]"; }
        @JsonAnySetter public void unknown(String key, Object value) {
            throw new IllegalArgumentException("注册请求包含不支持的字段");
        }
    }

    public record RegisterResult(String customerId, String realNameStatus) { }

    public record ProfileRequest(
            @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
            @Email @Size(max = 100) String email,
            @NotBlank String requestId) {
        @JsonAnySetter public void unknown(String key, Object value) {
            throw new IllegalArgumentException("仅允许更新手机号和邮箱");
        }
    }

    public record Profile(String customerId, String identityId, String realName,
                          String realNameStatus, String status, String phone, String email) { }

    public record AccountStatus(String accountId, String status) { }
    public record CustomerSummary(String customerId, String identityId, String realName,
                                  String status, List<AccountStatus> accounts) { }
    public record Page<T>(long total, int page, int pageSize, List<T> items) { }

    public record RoleRequest(
            @NotBlank @Size(max = 64) String identityId,
            @NotNull @Size(max = 100) Set<@NotBlank @Size(max = 32) String> accountScope,
            @NotNull Boolean enabled,
            @NotBlank @Size(max = 255) String reason,
            @NotBlank String requestId) {
        @JsonAnySetter public void unknown(String key, Object value) {
            throw new IllegalArgumentException("授权请求包含不支持的字段");
        }
    }

    public record Authorization(String identityId, Set<String> roles,
                                 Set<String> accountScope, long authorizationVersion) { }
}
