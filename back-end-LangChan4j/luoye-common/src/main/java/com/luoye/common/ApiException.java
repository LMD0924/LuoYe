package com.luoye.common;

/**
 * 业务 API 异常。
 *
 * <p>携带 HTTP 状态码和稳定错误码，由 API 层统一转换为 JSON 错误响应。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    /**
     * 创建业务 API 异常。
     *
     * @param status HTTP 状态码
     * @param code 面向客户端的稳定错误码
     * @param message 面向用户的错误信息
     */
    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** @return HTTP 状态码 */
    public int status() {
        return status;
    }

    /** @return 稳定错误码 */
    public String code() {
        return code;
    }
}