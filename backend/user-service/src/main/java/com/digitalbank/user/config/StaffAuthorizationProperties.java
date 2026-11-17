package com.digitalbank.user.config;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 服务端静态授权清单；修改后重启用户服务，旧内存会话随之失效。 */
@ConfigurationProperties("auth.staff")
public record StaffAuthorizationProperties(Map<String, Grant> grants) {
    public StaffAuthorizationProperties {
        grants = grants == null ? Map.of() : Map.copyOf(grants);
        if (grants.keySet().stream().anyMatch(id -> id.isBlank() || id.length() > 64)) {
            throw new IllegalArgumentException("授权身份标识必须为 1—64 个字符");
        }
    }

    public record Grant(Set<String> roles, Set<String> accountScope) {
        public Grant {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            accountScope = accountScope == null ? Set.of() : Set.copyOf(accountScope);
            if (!Set.of("OPERATIONS", "RISK").containsAll(roles)) {
                throw new IllegalArgumentException("后台角色仅支持 OPERATIONS / RISK");
            }
            if (accountScope.stream().anyMatch(id -> id.isBlank() || id.contains("*") || id.length() > 32)) {
                throw new IllegalArgumentException("账户范围必须为明确的账户业务标识，不能使用通配符");
            }
            if (roles.isEmpty() && !accountScope.isEmpty()) {
                throw new IllegalArgumentException("账户范围必须同时配置后台角色");
            }
        }
    }
}
