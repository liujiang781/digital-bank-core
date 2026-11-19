package com.digitalbank.common.exception;

import com.digitalbank.common.api.ErrorCode;
import java.util.Objects;

public class BizException extends RuntimeException {
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
