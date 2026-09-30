package com.luoye.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

/**
 * 发送消息请求 DTO。
 */
@Data
public class SendMessageDTO {

    /** 消息正文。 */
    @NotBlank(message = "消息内容不能为空")
    private String content;

    /** 是否流式（预留字段，当前统一走 SSE）。 */
    private Boolean stream;

    /** 重新生成所基于的消息（预留字段）。 */
    private UUID regenerateOf;
}
