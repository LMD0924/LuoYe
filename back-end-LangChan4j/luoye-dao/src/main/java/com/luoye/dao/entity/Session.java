package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话实体，对应表 {@code sessions}。
 */
@Data
@TableName("sessions")
public class Session {

    /** 主键 UUID，由 MyBatis-Plus 分配。 */
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    /** 所属用户 ID。 */
    private UUID userId;

    /** 会话标题，可为空。 */
    private String title;

    /** 状态：active | archived。 */
    private String status;

    private Instant createdAt;

    private Instant updatedAt;

    /** 软删除时间戳；查询由 service 层 isNull 过滤。 */
    private Instant deletedAt;
}
