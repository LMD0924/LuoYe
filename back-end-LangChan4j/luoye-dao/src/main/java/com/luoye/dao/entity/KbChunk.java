package com.luoye.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.luoye.dao.handler.VectorTypeHandler;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** 知识库文本块，对应表 {@code kb_chunks}。 */
@Data
@TableName(value = "kb_chunks", autoResultMap = true)
public class KbChunk {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID documentId;
    private UUID userId;
    private Integer seq;
    private String content;
    private String sourceHash;
    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    private float[] embedding;
    private Integer tokenCount;
    private String metadata;
    private String embeddingModel;
    private Instant createdAt;
    private Instant updatedAt;
}
