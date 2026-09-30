package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 记忆纠偏记录实体，对应表 {@code memory_correction}。
 *
 * <p>每次用户纠正记忆时留痕，供审计与后续抽取判断。
 */
@Data
@TableName("memory_correction")
public class MemoryCorrection {

    /** 主键 UUID，由 MyBatis-Plus 分配。 */
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    /** 所属用户 ID。 */
    private UUID userId;

    /** 被纠正的记忆 ID。 */
    private UUID memoryId;

    /** 纠正后的内容。 */
    private String correctedValue;

    /** 触发纠偏的来源消息 ID。 */
    private UUID sourceMessageId;

    private Instant createdAt;
}
