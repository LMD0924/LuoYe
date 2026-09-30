package com.luoye.api.vo;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息视图对象。
 *
 * @param id 消息 ID
 * @param sessionId 所属会话 ID
 * @param seq 会话内序号
 * @param role 角色
 * @param content 正文
 * @param status 状态
 * @param runId 本轮生成 ID
 * @param createdAt 创建时间
 */
public record MessageVO(
        UUID id,
        UUID sessionId,
        Integer seq,
        String role,
        String content,
        String status,
        UUID runId,
        Instant createdAt) {
}
