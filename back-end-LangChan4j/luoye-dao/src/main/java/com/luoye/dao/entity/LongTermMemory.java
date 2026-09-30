package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.luoye.dao.handler.VectorTypeHandler;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 长期记忆实体，对应表 {@code long_term_memory}。
 *
 * <p>{@code autoResultMap = true} 使 embedding 字段的自定义 TypeHandler
 * 在 BaseMapper 的内置方法中生效。
 */
@Data
@TableName(value = "long_term_memory", autoResultMap = true)
public class LongTermMemory {

    /** 主键 UUID，由 MyBatis-Plus 分配。 */
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    /** 所属用户 ID。 */
    private UUID userId;

    /** 记忆正文。 */
    private String content;

    /** 记忆类型：fact | preference | event | profile。 */
    private String memoryType;

    /** 来源：explicit | inferred。 */
    private String source;

    /** 置信度：high | medium | low（仅 inferred 必填）。 */
    private String confidence;

    /** 状态：pending | active | stale | deleted。 */
    private String status;

    /** 文本向量（固定 1536 维），通过自定义 TypeHandler 与 pgvector 互转。 */
    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    private float[] embedding;

    /** 重要度权重。 */
    private Double importanceWeight;

    /** 访问次数。 */
    private Integer accessCount;

    /** 最近访问时间。 */
    private Instant lastAccessAt;

    /** 是否永不衰减。 */
    private Boolean neverDecay;

    /** 被纠偏次数。 */
    private Integer correctionCount;

    /** 来源会话 ID。 */
    private UUID originSessionId;

    /** 来源消息 ID。 */
    private UUID originMessageId;

    /** 下次复查时间。 */
    private Instant nextReviewAt;

    /** 正文 SHA-256 哈希，用于去重与抑制。 */
    private String contentHash;

    /** 生成向量所用的嵌入模型标识，隔离不同嵌入空间。 */
    private String embeddingModel;

    /** 衰减分数。 */
    private Double decayScore;

    /** 是否进入冷区。 */
    private Boolean cold;

    private Instant createdAt;

    private Instant updatedAt;

    /** 软删除时间戳；不使用 MP 自动逻辑删除，查询由 service 层 isNull 过滤。 */
    private Instant deletedAt;
}
