package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** 待办实体，对应表 {@code todos}。 */
@Data
@TableName("todos")
public class Todo {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID userId;
    private String title;
    private String description;
    private Instant dueAt;
    private Instant remindAt;
    private String status;
    private String originText;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
}
