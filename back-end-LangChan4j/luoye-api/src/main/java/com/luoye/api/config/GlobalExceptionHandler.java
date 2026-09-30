package com.luoye.api.config;

import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.Result;
import com.luoye.common.response.ResultCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p>业务异常转换为对应 HTTP 状态码 + {@link Result} JSON；
 * 参数校验异常返回 400；其余未预期异常统一兜底 500，不暴露堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** @param e 业务异常 @return 异常携带的状态码与错误 JSON */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * @param e Bean Validation 校验异常
     * @return 400，message 取第一条字段错误提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> invalidArgument(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? ResultCode.BAD_REQUEST.getMessage()
                : fieldError.getDefaultMessage();
        return ResponseEntity.status(ResultCode.BAD_REQUEST.getStatus())
                .body(Result.error(ResultCode.BAD_REQUEST, message));
    }

    /** @param e 未单独映射的异常 @return 通用 500 响应 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> other(Exception e) {
        // 不记录/返回堆栈或数据库错误细节，避免泄露内部实现。
        return ResponseEntity.status(ResultCode.INTERNAL_ERROR.getStatus())
                .body(Result.error(ResultCode.INTERNAL_ERROR));
    }
}
