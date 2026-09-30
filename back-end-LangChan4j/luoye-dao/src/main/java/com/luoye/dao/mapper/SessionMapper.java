package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/**
 * 会话 Mapper。
 *
 * <p>基础 CRUD 由 {@link BaseMapper} 提供；追加消息时的行锁为自定义 SQL。
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /**
     * 锁定会话行，持续到事务结束，串行化同一会话的消息追加。
     *
     * @param sessionId 会话 ID
     * @return 会话 ID
     */
    @Select("SELECT id FROM sessions WHERE id=#{sessionId} FOR UPDATE")
    UUID selectForUpdate(@Param("sessionId") UUID sessionId);
}
