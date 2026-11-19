package com.digitalbank.common.auth;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class InternalServiceToken {
    public static final String HEADER = "X-Service-Token";
    private final String value;

    public InternalServiceToken(String value) {
        this.value = value == null ? "" : value;
    }

    public String configuredValue() {
        if (value.length() < 32) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "认证服务尚未配置内部访问密钥");
        }
        return value;
    }

    public void requireMatch(String supplied) {
        byte[] expected = configuredValue().getBytes(StandardCharsets.UTF_8);
        byte[] actual = supplied == null
                ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw AuthTokens.unauthenticated();
        }
    }
}
