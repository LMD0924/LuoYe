package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

/**
 * 遗忘抑制实体，对应表 {@code memory_suppression}。
 *
 * <p>仅保留被删除/纠正事实的正文哈希，隐式抽取不得重新引入该事实。
 * 表为复合主键 (user_id, content_hash)；此处以 user_id 作为 MP 主键映射，
 * 写入统一走 Mapper 的 ON CONFLICT 语句。
 */
@Data
@TableName("memory_suppression")
public class MemorySuppression {

    /** 所属用户 ID（复合主键之一）。 */
    @TableId(value = "user_id", type = IdType.INPUT)
    private UUID userId;

    /** 被抑制事实的正文哈希（复合主键之一）。 */
    @TableField("content_hash")
    private String contentHash;
}
