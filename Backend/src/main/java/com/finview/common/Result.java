package com.finview.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应包：{ "code": 0, "message": "", "data": {} }
 * code = 0 表示成功，非 0 为业务错误码（与前端 ApiError 对齐）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    public static final int SUCCESS = 0;

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS, "", data);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
