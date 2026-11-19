package com.digitalbank.common.trace;

import java.util.UUID;
import java.util.regex.Pattern;

public final class TraceIds {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private TraceIds() { }

    public static String normalize(String value) {
        return value != null && VALID.matcher(value).matches()
                ? value : UUID.randomUUID().toString();
    }
}
