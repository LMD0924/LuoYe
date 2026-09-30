package com.luoye.common.response;

import lombok.Getter;

/**
 * 统一响应结构 {@code Result<T>}。
 *
 * <p>所有普通（非 SSE）Controller 接口统一返回本类型：
 * {@code code} 为错误码（成功为 "0"），{@code message} 为提示，{@code data} 为业务数据。
 */
@Getter
public class Result<T> {

    private String code;
    private String message;
    private T data;

    public Result() {
    }

    public Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** @return 无数据的成功响应 */
    public static <T> Result<T> ok() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /** @param data 业务数据 @return 携带数据的成功响应 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** @param resultCode 错误码枚举 @return 使用枚举默认提示的错误响应 */
    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /** @param resultCode 错误码枚举 @param message 自定义提示 @return 自定义提示的错误响应 */
    public static <T> Result<T> error(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }

    /** @param code 自定义错误码 @param message 自定义提示 @return 完全自定义的错误响应 */
    public static <T> Result<T> error(String code, String message) {
        return new Result<>(code, message, null);
    }
}
