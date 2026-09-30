package com.luoye.api.vo;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话视图对象。
 *
 * @param id 会话 ID
 * @param title 标题
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间（可为空）
 */
public record SessionVO(UUID id, String title, Instant createdAt, Instant updatedAt) {
}
