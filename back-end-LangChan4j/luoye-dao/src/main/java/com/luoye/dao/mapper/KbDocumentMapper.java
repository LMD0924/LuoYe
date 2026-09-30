package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/** 知识库文档 Mapper。 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    @Select("SELECT * FROM kb_documents WHERE user_id=#{owner} AND deleted_at IS NULL "
            + "ORDER BY coalesce(updated_at,created_at) DESC")
    List<KbDocument> selectOwned(@Param("owner") UUID owner);

    @Select("SELECT * FROM kb_documents WHERE user_id=#{owner} AND id=#{id} AND deleted_at IS NULL")
    KbDocument selectOwnedById(@Param("owner") UUID owner, @Param("id") UUID id);

    @Select("SELECT * FROM kb_documents WHERE user_id=#{owner} AND source_type='note' "
            + "AND source_ref=#{noteId} AND deleted_at IS NULL LIMIT 1")
    KbDocument selectByNote(@Param("owner") UUID owner, @Param("noteId") UUID noteId);

    @Update("UPDATE kb_documents SET deleted_at=now(),updated_at=now() "
            + "WHERE user_id=#{owner} AND id=#{id} AND deleted_at IS NULL")
    int softDelete(@Param("owner") UUID owner, @Param("id") UUID id);
}
