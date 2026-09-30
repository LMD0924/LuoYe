package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

/**
 * 记忆抽取游标实体，对应表 {@code memory_extraction_cursor}。
 *
 * <p>记录每个会话已抽取到的消息序号，重启后不会重复处理整段历史。
 * 主键为会话 ID（外部传入，不自动生成）。
 */
@Data
@TableName("memory_extraction_cursor")
public class MemoryExtractionCursor {

    /** 会话 ID（主键）。 */
    @TableId(type = IdType.INPUT)
    private UUID sessionId;

    /** 已抽取的最后一条消息序号。 */
    private Integer lastSeq;
}
