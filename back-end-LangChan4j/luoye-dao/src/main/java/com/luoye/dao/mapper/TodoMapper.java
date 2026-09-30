package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.Todo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/** 待办 Mapper。 */
@Mapper
public interface TodoMapper extends BaseMapper<Todo> {

    @Select("<script>SELECT * FROM todos WHERE user_id=#{owner} AND deleted_at IS NULL"
            + "<if test='status != null and status != \"\"'> AND status=#{status}</if>"
            + " ORDER BY coalesce(due_at,created_at) ASC,created_at DESC</script>")
    List<Todo> selectOwned(@Param("owner") UUID owner, @Param("status") String status);

    @Select("SELECT * FROM todos WHERE user_id=#{owner} AND id=#{id} AND deleted_at IS NULL")
    Todo selectOwnedById(@Param("owner") UUID owner, @Param("id") UUID id);

    @Select("SELECT * FROM todos WHERE user_id=#{owner} AND deleted_at IS NULL AND status='open' "
            + "AND remind_at IS NOT NULL AND remind_at <= now() "
            + "ORDER BY remind_at LIMIT 20")
    List<Todo> selectDueReminders(@Param("owner") UUID owner);

    @Update("UPDATE todos SET deleted_at=now(),updated_at=now() "
            + "WHERE user_id=#{owner} AND id=#{id} AND deleted_at IS NULL")
    int softDelete(@Param("owner") UUID owner, @Param("id") UUID id);
}
