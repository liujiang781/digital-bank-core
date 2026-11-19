package com.digitalbank.transaction.security;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Service authentication boundary for internal demo endpoints; an unset token disables access. */
@Component
public class InternalAccess {
    private final byte[] expectedToken;

    public InternalAccess(@Value("${transaction.internal-token:}") String token) {
        expectedToken = token == null || token.isBlank() ? new byte[0]
                : token.getBytes(StandardCharsets.UTF_8);
        if (expectedToken.length > 0 && expectedToken.length < 32) {
            throw new IllegalArgumentException("transaction.internal-token must contain at least 32 UTF-8 bytes");
        }
    }

    public void require(HttpServletRequest request) {
        String supplied = request.getHeader("X-Internal-Token");
        byte[] candidate = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        boolean matches = MessageDigest.isEqual(expectedToken, candidate);
        if (expectedToken.length == 0 || !matches) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "内部服务身份校验失败");
        }
    }
}
