package com.digitalbank.transaction.security;

import com.digitalbank.common.auth.SessionIdentity;
import java.security.Principal;
import java.util.Objects;
import java.util.Set;

/**
 * Server-authenticated identity supplied by S1; never constructed from client identity headers.
 * 运营/风控接口沿用账户服务的口径：角色与账户范围只来自服务端核验的会话。
 */
public record Actor(String identityId, String customerId, Set<String> roles,
                    Set<String> accountScope) implements Principal {
    public Actor {
        Objects.requireNonNull(identityId, "identityId");
        if (identityId.isBlank()) throw new IllegalArgumentException("identityId must not be blank");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        accountScope = accountScope == null ? Set.of() : Set.copyOf(accountScope);
    }

    /** 普通客户：只带身份标识，角色与账户范围为空。 */
    public Actor(String identityId) { this(identityId, null, Set.of(), Set.of()); }

    /** 后台人员：从 S1 过滤器核验后的会话构造，不读取客户端头。 */
    public Actor(SessionIdentity identity) {
        this(identity.identityId(), identity.customerId(), identity.roles(), identity.accountScope());
    }

    @Override
    public String getName() { return identityId; }
}
