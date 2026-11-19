package com.digitalbank.common.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.security.Principal;
import java.util.Set;

/** 对外使用业务标识，不暴露凭据、密码哈希或数据库主键。 */
public record SessionIdentity(String customerId, String identityId,
                              Set<String> roles, Set<String> accountScope,
                              long authorizationVersion) implements Principal {
    public SessionIdentity {
        // 兼容旧认证响应：缺失授权字段时只获得普通客户权限。
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        accountScope = accountScope == null ? Set.of() : Set.copyOf(accountScope);
    }

    public SessionIdentity(String customerId, String identityId) {
        this(customerId, identityId, Set.of(), Set.of());
    }

    public SessionIdentity(String customerId, String identityId, Set<String> roles, Set<String> accountScope) {
        this(customerId, identityId, roles, accountScope, 0);
    }

    @Override
    @JsonIgnore
    public String getName() {
        return identityId;
    }

    @JsonIgnore
    public boolean isValid() {
        return customerId != null && !customerId.isBlank()
                && identityId != null && !identityId.isBlank();
    }
}
