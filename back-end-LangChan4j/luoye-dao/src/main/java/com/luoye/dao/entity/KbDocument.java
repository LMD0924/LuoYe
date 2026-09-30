package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** 知识库文档，对应表 {@code kb_documents}。 */
@Data
@TableName("kb_documents")
public class KbDocument {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID userId;
    private String title;
    /** upload | note */
    private String sourceType;
    private UUID sourceRef;
    private String fileName;
    private String mime;
    private String docMeta;
    /** indexing | ready | failed */
    private String status;
    private Integer chunkCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
}
