package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.MemoryExtractionCursor;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/**
 * 记忆抽取游标 Mapper。
 *
 * <p>基础 CRUD 由 {@link BaseMapper} 提供；游标推进用 ON CONFLICT 保证幂等。
 */
@Mapper
public interface MemoryExtractionCursorMapper extends BaseMapper<MemoryExtractionCursor> {

    /**
     * 推进成功抽取的游标；重复提交取较大值，重启后不会重复处理历史。
     *
     * @param sessionId 会话 ID
     * @param seq 已抽取到的消息序号
     */
    @Insert("INSERT INTO memory_extraction_cursor(session_id,last_seq) VALUES(#{sessionId},#{seq}) "
            + "ON CONFLICT (session_id) DO UPDATE SET "
            + "last_seq=greatest(memory_extraction_cursor.last_seq,excluded.last_seq)")
    int checkpoint(@Param("sessionId") UUID sessionId, @Param("seq") int seq);

    /**
     * 清空记忆时跳过该用户所有旧对话，防止下一轮隐式抽取重新导入清空前内容。
     *
     * @param owner 用户 ID
     */
    @Insert("INSERT INTO memory_extraction_cursor(session_id,last_seq) "
            + "SELECT s.id,coalesce(max(m.seq),0) FROM sessions s "
            + "LEFT JOIN messages m ON m.session_id=s.id "
            + "WHERE s.user_id=#{owner} GROUP BY s.id "
            + "ON CONFLICT (session_id) DO UPDATE SET last_seq=excluded.last_seq")
    int skipHistory(@Param("owner") UUID owner);
}
