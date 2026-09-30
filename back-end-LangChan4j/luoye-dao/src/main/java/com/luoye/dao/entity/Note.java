package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** 笔记实体，对应表 {@code notes}。 */
@Data
@TableName("notes")
public class Note {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID userId;
    private String title;
    private String content;
    private String contentHash;
    private String status;
    private UUID kbDocumentId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
}
