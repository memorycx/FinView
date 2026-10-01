package com.finview.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：统一转换为前端约定的响应包结构。
 * 业务错误（400/404）保持 HTTP 200，仅通过 body.code 表达，避免前端
 * 退化成「请求失败: {status}」而丢失后端中文提示。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        log.warn("业务异常 code={}, message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.ok(Result.error(ex.getCode(), ex.getMessage()));
    }

    /**
     * @Valid 校验失败：把 DTO 上写的中文提示原样返回，
     * 否则会被下面的兜底分支吞成「服务端内部错误」，用户看到的是密码太短却提示服务端崩了。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验失败：{}", message);
        return ResponseEntity.ok(Result.error(400, message));
    }

    /** 请求体不是合法 JSON / 字段类型对不上 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败：{}", ex.getMessage());
        return ResponseEntity.ok(Result.error(400, "请求参数格式不正确"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("服务端内部错误", ex);
        return ResponseEntity.ok(Result.error(500, "服务端内部错误"));
    }
}
