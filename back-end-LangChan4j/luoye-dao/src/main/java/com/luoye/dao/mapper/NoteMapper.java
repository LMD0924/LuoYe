package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.Note;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/** 笔记 Mapper。 */
@Mapper
public interface NoteMapper extends BaseMapper<Note> {

    @Select("SELECT * FROM notes WHERE user_id=#{owner} AND deleted_at IS NULL "
            + "ORDER BY coalesce(updated_at,created_at) DESC")
    List<Note> selectOwned(@Param("owner") UUID owner);

    @Select("SELECT * FROM notes WHERE user_id=#{owner} AND id=#{id} AND deleted_at IS NULL")
    Note selectOwnedById(@Param("owner") UUID owner, @Param("id") UUID id);

    @Update("UPDATE notes SET deleted_at=now(),updated_at=now(),status='deleted' "
            + "WHERE user_id=#{owner} AND id=#{id} AND deleted_at IS NULL")
    int softDelete(@Param("owner") UUID owner, @Param("id") UUID id);
}
