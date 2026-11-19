package com.digitalbank.user.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.trace.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.digitalbank.user")
public class UserStorageExceptionHandler {
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(DataAccessException failure, HttpServletRequest request) {
        // 数据库异常不暴露 SQL、连接信息或用户参数。
        return ResponseEntity.status(503).body(ApiResponse.failure(ErrorCode.SERVICE_UNAVAILABLE,
                "用户数据存储暂时不可用，请稍后重试", (String) request.getAttribute(TraceIds.MDC_KEY)));
    }
}
