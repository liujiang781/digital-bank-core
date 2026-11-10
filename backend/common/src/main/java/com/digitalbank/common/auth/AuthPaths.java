package com.digitalbank.common.auth;

import java.util.Set;

public final class AuthPaths {
    private static final Set<String> HEALTH_PATHS = Set.of(
            "/actuator/health",
            "/api/v1/user/health",
            "/api/v1/account/health",
            "/api/v1/transaction/health",
            "/api/v1/risk/health"
    );

    private AuthPaths() {
    }

    // 白名单精确匹配路径和方法，避免把整个 /auth/** 意外开放。
    public static boolean isPublic(String method, String path) {
        if ("POST".equals(method) && "/api/v1/auth/login".equals(path)) {
            return true;
        }
        boolean readOnly = "GET".equals(method) || "HEAD".equals(method);
        return readOnly && HEALTH_PATHS.contains(path);
    }
}
