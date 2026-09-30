package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

/**
 * 用户 Mapper。
 *
 * <p>基础 CRUD 由 {@link BaseMapper} 提供；记忆修订号的乐观并发控制为自定义 SQL。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** @return 当前用户记忆修订号，用于拒绝网络调用期间已过期的抽取结果。 */
    @Select("SELECT memory_revision FROM users WHERE id=#{owner}")
    Long selectRevision(@Param("owner") UUID owner);

    /**
     * 锁定用户记忆域所在行；同用户的管理操作和抽取提交因此串行执行。
     *
     * @return 当前修订号（已加行锁）
     */
    @Select("SELECT memory_revision FROM users WHERE id=#{owner} FOR UPDATE")
    Long selectRevisionForUpdate(@Param("owner") UUID owner);

    /** 一次原子变更提交后递增修订号。 */
    @Update("UPDATE users SET memory_revision=memory_revision+1 WHERE id=#{owner}")
    int incrementRevision(@Param("owner") UUID owner);
}
