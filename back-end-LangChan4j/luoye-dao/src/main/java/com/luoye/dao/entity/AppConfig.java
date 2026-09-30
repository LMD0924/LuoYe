package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** 用户配置，对应表 {@code configs}。 */
@Data
@TableName("configs")
public class AppConfig {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID userId;
    private String configKey;
    private String configValue;
    private Instant updatedAt;
}
