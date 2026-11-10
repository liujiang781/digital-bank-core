package com.digitalbank.common.auth;

/** 对外使用业务标识，不暴露凭据、密码哈希或数据库主键。 */
public record SessionIdentity(String customerId, String identityId) {
    public boolean isValid() {
        return customerId != null && !customerId.isBlank()
                && identityId != null && !identityId.isBlank();
    }
}
