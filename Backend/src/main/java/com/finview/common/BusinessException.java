package com.finview.common;

import lombok.Getter;

/**
 * 业务异常：携带与前端约定的业务错误码（400 / 404 等）。
 * 为了让前端能拿到约定的中文 message，异常经全局处理后仍以 HTTP 200 +
 * 响应包 { code, message } 返回（前端依据 body.code 抛出 ApiError）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }
}
