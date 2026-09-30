package com.luoye.common.exception;

import com.luoye.common.response.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>业务规则校验失败时抛出，携带 HTTP 状态码和稳定错误码，
 * 由 API 层的全局异常处理器统一转换为 {@code Result} JSON 响应。
 * 替代旧版本的 {@code ApiException}。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** HTTP 状态码。 */
    private final int status;
    /** 面向客户端的稳定错误码。 */
    private final String code;

    /**
     * @param resultCode 错误码枚举，状态码、错误码、提示均取自枚举
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.status = resultCode.getStatus();
        this.code = resultCode.getCode();
    }

    /**
     * @param resultCode 错误码枚举（提供状态码与错误码）
     * @param message 自定义用户提示
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.status = resultCode.getStatus();
        this.code = resultCode.getCode();
    }

    /**
     * @param status HTTP 状态码
     * @param code 稳定错误码
     * @param message 用户提示
     */
    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
