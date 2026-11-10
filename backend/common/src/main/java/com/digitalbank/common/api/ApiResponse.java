package com.digitalbank.common.api;

public record ApiResponse<T>(String code, String message, T data, String traceId) {
    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>("SUCCEEDED", "请求成功", data, traceId);
    }

    public static ApiResponse<Void> failure(ErrorCode error, String message, String traceId) {
        return new ApiResponse<>(error.name(), message, null, traceId);
    }
}
