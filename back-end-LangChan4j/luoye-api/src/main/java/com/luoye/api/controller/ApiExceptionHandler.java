package com.luoye.api.controller;

import java.util.Map;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import com.luoye.common.ApiException;

/**
 * 控制器异常响应转换，依赖 Spring MVC 的全局异常处理机制。
 *
 * <p>只向客户端暴露业务错误或通用提示，不返回底层异常详情。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** @param e 带业务错误码的异常 @return 指定 HTTP 状态及错误 JSON */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    /**
     * 处理未单独映射的异常。
     *
     * <p>已知限制：当前也会捕获 ResponseStatusException，将其原状态覆盖为 500。
     *
     * @param e 未被业务异常分支处理的异常
     * @return 通用 500 响应
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        return ResponseEntity.status(500)
                .body(Map.of("code", "internal_error", "message", "服务暂不可用"));
    }
}