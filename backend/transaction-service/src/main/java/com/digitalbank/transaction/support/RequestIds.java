package com.digitalbank.transaction.support;

import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import java.util.UUID;
import java.util.regex.Pattern;

public final class RequestIds {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RequestIds() { }

    /** requestId 放请求体（UUID）；同时提供 X-Request-Id 时两者必须一致，否则 400。 */
    public static String resolve(String bodyRequestId, String headerRequestId) {
        if (bodyRequestId == null && headerRequestId == null) throw invalid();
        String body = bodyRequestId == null ? null : canonical(bodyRequestId);
        String header = headerRequestId == null ? null : canonical(headerRequestId);
        if (body != null && header != null && !body.equals(header)) throw invalid();
        return body != null ? body : header;
    }

    private static String canonical(String value) {
        if (!UUID_TEXT.matcher(value).matches()) throw invalid();
        return UUID.fromString(value).toString();
    }

    private static BizException invalid() {
        return new BizException(ErrorCode.INVALID_ARGUMENT, "requestId 必须为 UUID，且请求头与请求体必须一致");
    }
}
