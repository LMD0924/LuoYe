package com.luoye.common.response;

import lombok.Getter;

/**
 * 统一响应错误码枚举。
 *
 * <p>每个枚举项携带：HTTP 状态码 {@code status}、稳定错误码 {@code code}、
 * 默认提示 {@code message}。错误码保持与 M1 版本一致，前端无需改动判断逻辑。
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "0", "ok"),
    BAD_REQUEST(400, "bad_request", "请求参数错误"),
    INVALID_CONTENT(400, "invalid_content", "消息不能为空且不能超过 20000 字符"),
    UNAUTHORIZED(401, "unauthorized", "未认证或认证已失效"),
    FORBIDDEN(403, "forbidden", "没有访问权限"),
    NOT_FOUND(404, "not_found", "资源不存在"),
    SESSION_NOT_FOUND(404, "session_not_found", "会话不存在"),
    SESSION_BUSY(409, "session_busy", "该会话正在生成，请等待或停止当前回复"),
    INTERNAL_ERROR(500, "internal_error", "服务暂不可用");

    /** HTTP 状态码，由全局异常处理器写入响应行。 */
    private final int status;
    /** 面向客户端的稳定错误码（字符串），作为 Result.code。 */
    private final String code;
    /** 默认用户提示，可在抛出异常时覆盖。 */
    private final String message;

    ResultCode(int status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
