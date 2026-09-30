package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.KbChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** 知识库文本块 Mapper。 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    String COLS = "id,document_id,user_id,seq,content,source_hash,token_count,metadata,"
            + "embedding_model,created_at,updated_at";

    @Select("SELECT " + COLS + " FROM kb_chunks WHERE document_id=#{documentId} ORDER BY seq")
    List<KbChunk> selectByDocument(@Param("documentId") UUID documentId);

    @Delete("DELETE FROM kb_chunks WHERE document_id=#{documentId}")
    int deleteByDocument(@Param("documentId") UUID documentId);

    @Insert("INSERT INTO kb_chunks(id,document_id,user_id,seq,content,source_hash,embedding,"
            + "token_count,metadata,embedding_model,updated_at) "
            + "VALUES(#{id},#{documentId},#{owner},#{seq},#{content},#{hash},"
            + "CAST(#{vector} AS vector),#{tokens},#{metadata},#{model},now())")
    int insertChunk(@Param("id") UUID id,
                    @Param("documentId") UUID documentId,
                    @Param("owner") UUID owner,
                    @Param("seq") int seq,
                    @Param("content") String content,
                    @Param("hash") String hash,
                    @Param("vector") String vector,
                    @Param("tokens") Integer tokens,
                    @Param("metadata") String metadata,
                    @Param("model") String model);

    @Select("SELECT c.id,c.document_id,c.user_id,c.seq,c.content,c.source_hash,c.token_count,"
            + "c.metadata,c.embedding_model,c.created_at,c.updated_at "
            + "FROM kb_chunks c JOIN kb_documents d ON d.id=c.document_id "
            + "WHERE c.user_id=#{owner} AND d.deleted_at IS NULL AND d.status='ready' "
            + "AND c.embedding_model=#{model} AND c.embedding IS NOT NULL "
            + "AND (c.embedding <=> CAST(#{vector} AS vector)) <= #{maxDistance} "
            + "ORDER BY c.embedding <=> CAST(#{vector} AS vector) LIMIT #{limit}")
    List<KbChunk> selectNearest(@Param("owner") UUID owner,
                                @Param("vector") String vector,
                                @Param("model") String model,
                                @Param("limit") int limit,
                                @Param("maxDistance") double maxDistance);

    @Select("SELECT c.id,c.document_id,c.user_id,c.seq,c.content,c.source_hash,c.token_count,"
            + "c.metadata,c.embedding_model,c.created_at,c.updated_at "
            + "FROM kb_chunks c JOIN kb_documents d ON d.id=c.document_id "
            + "WHERE c.user_id=#{owner} AND d.deleted_at IS NULL AND d.status='ready' AND ("
            + "strpos(lower(c.content),lower(#{query}))>0 "
            + "OR to_tsvector('simple',c.content) @@ plainto_tsquery('simple',#{query}) "
            + "OR similarity(c.content,#{query})>0.08) "
            + "ORDER BY similarity(c.content,#{query}) DESC LIMIT #{limit}")
    List<KbChunk> searchByText(@Param("owner") UUID owner,
                               @Param("query") String query,
                               @Param("limit") int limit);
}
