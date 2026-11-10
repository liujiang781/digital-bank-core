package com.digitalbank.common.web;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.api.ErrorCode;
import com.digitalbank.common.exception.BizException;
import com.digitalbank.common.trace.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> business(BizException ex, HttpServletRequest request) {
        return failure(ex.getErrorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, ServletRequestBindingException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception ex, HttpServletRequest request) {
        return failure(ErrorCode.INVALID_ARGUMENT, "请求参数不合法", request);
    }

    // 保留 Spring 的 404/405/415 等 HTTP 错误语义，避免误报为 500。
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiResponse<Void>> http(Exception ex,
            HttpServletRequest request) {
        var error = (org.springframework.web.ErrorResponse) ex;
        return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).body(new ApiResponse<>(
                "HTTP_" + error.getStatusCode().value(), "请求路径、方法或媒体类型不支持", null, trace(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request failure", ex);
        return failure(ErrorCode.INTERNAL_ERROR, "系统暂时不可用，请稍后查询处理结果", request);
    }

    private ResponseEntity<ApiResponse<Void>> failure(ErrorCode code, String message,
            HttpServletRequest request) {
        return ResponseEntity.status(code.status()).body(ApiResponse.failure(code, message, trace(request)));
    }

    private String trace(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIds.MDC_KEY);
    }
}
