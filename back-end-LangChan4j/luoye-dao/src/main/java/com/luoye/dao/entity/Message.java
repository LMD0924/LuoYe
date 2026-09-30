package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息实体，对应表 {@code messages}（短期记忆载体）。
 */
@Data
@TableName("messages")
public class Message {

    /** 主键 UUID，由 MyBatis-Plus 分配。 */
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    /** 所属会话 ID。 */
    private UUID sessionId;

    /** 会话内递增序号，由 service 层在事务中分配。 */
    private Integer seq;

    /** 角色：USER | ASSISTANT | TOOL | SYSTEM | MEMORY。 */
    private String role;

    /** 消息正文。 */
    private String content;

    /** 状态：completed | interrupped | generated（沿用数据库原始拼写）。 */
    private String status;

    /** 本轮生成任务 ID，关联一问一答。 */
    private UUID runId;

    /** token 用量，供应商未报告时为空。 */
    private Integer tokensUsed;

    private Instant createdAt;
}
