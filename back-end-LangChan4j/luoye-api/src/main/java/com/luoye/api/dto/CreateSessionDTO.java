package com.luoye.api.dto;

import lombok.Data;

/**
 * 创建会话请求 DTO；title 可省略。
 */
@Data
public class CreateSessionDTO {

    /** 会话标题，为空时前端显示“未命名会话”。 */
    private String title;
}
