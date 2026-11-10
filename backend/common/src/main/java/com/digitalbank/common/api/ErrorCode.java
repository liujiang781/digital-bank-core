package com.digitalbank.common.api;

// 与实验二接口契约保持一致；HTTP 状态通过 status() 获取。
public enum ErrorCode {
    INVALID_ARGUMENT(400), UNAUTHENTICATED(401), FORBIDDEN(403),
    ACCOUNT_NOT_FOUND(404), TRADE_NOT_FOUND(404), REQUEST_CONFLICT(409),
    ACCOUNT_LIMIT_REACHED(422), ACCOUNT_STATUS_INVALID(422),
    INSUFFICIENT_AVAILABLE_BALANCE(422), SINGLE_LIMIT_EXCEEDED(422),
    DAILY_LIMIT_EXCEEDED(422), ACCOUNT_NOT_CLOSABLE(422), INTERNAL_ERROR(500), SERVICE_UNAVAILABLE(503);

    private final int status;
    ErrorCode(int status) { this.status = status; }
    public int status() { return status; }
}
