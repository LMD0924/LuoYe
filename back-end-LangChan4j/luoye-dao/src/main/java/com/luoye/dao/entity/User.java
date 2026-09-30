package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户实体，对应表 {@code users}（单用户方案，为多用户预留）。
 */
@Data
@TableName("users")
public class User {

    /** 主键 UUID，由 MyBatis-Plus 分配。 */
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    /** 登录名，唯一。 */
    private String username;

    /** 展示名。 */
    private String displayName;

    /** BCrypt 密码哈希。 */
    private String passwordHash;

    /** 记忆修订号（M2 乐观并发控制使用）。 */
    private Long memoryRevision;

    private Instant createdAt;

    private Instant updatedAt;

    /** 软删除时间戳；不使用 MP 自动逻辑删除，查询由 service 层 isNull 过滤。 */
    private Instant deletedAt;
}
