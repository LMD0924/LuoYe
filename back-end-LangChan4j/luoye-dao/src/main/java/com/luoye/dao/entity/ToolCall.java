package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** 工具调用审计，对应表 {@code tool_calls}。 */
@Data
@TableName("tool_calls")
public class ToolCall {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID userId;
    private UUID sessionId;
    private String toolName;
    private String input;
    private String output;
    private String status;
    private Integer latencyMs;
    private UUID confirmToken;
    private Instant calledAt;
}
