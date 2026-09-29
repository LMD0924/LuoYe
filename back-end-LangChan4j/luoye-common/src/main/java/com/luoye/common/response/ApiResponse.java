package com.luoye.common.response;

import lombok.Getter;
import lombok.Setter;

/**
 * 统一 API 返回体。
 * 约定：{@code data} 为业务数据；错误时 {@code code} 非 SUCCESS。
 */
@Getter
@Setter
public class ApiResponse<T> {

    public static final String SUCCESS = "0";

    private String code = SUCCESS;
    private String message = "ok";
    private T data;
    private String traceId;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setData(data);
        return r;
    }

    public static ApiResponse<Void> error(String code, String message) {
        ApiResponse<Void> r = new ApiResponse<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}