package com.digitalbank.common.auth;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.util.List;
import java.util.regex.Pattern;

public final class AuthTokens {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private AuthTokens() {
    }

    public static String requireBearer(List<String> headers) {
        if (headers == null || headers.size() != 1) {
            throw unauthenticated();
        }
        String value = headers.getFirst();
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw unauthenticated();
        }
        String token = value.substring(7);
        if (!TOKEN.matcher(token).matches()) {
            throw unauthenticated();
        }
        return token;
    }

    public static BizException unauthenticated() {
        return new BizException(ErrorCode.UNAUTHENTICATED, "登录凭证无效或已过期，请重新登录");
    }
}
