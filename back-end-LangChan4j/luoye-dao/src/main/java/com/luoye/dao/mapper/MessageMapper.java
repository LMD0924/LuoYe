package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 消息 Mapper。
 *
 * <p>基础 CRUD 由 {@link BaseMapper} 提供；上下文窗口恢复和序号分配为自定义 SQL。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 取会话最近的已完成用户/助手消息（SQL 倒序），调用方负责 reverse 恢复自然语序。
     *
     * @param sessionId 会话 ID
     * @param limit 最大消息条数
     * @return 倒序的最近消息
     */
    @Select("SELECT * FROM messages WHERE session_id=#{sessionId} "
            + "AND role IN ('USER','ASSISTANT') AND status='completed' "
            + "ORDER BY seq DESC LIMIT #{limit}")
    List<Message> selectRecent(@Param("sessionId") UUID sessionId, @Param("limit") int limit);

    /**
     * @param sessionId 会话 ID
     * @return 会话内下一个消息序号（空会话为 1）
     */
    @Select("SELECT coalesce(max(seq),0)+1 FROM messages WHERE session_id=#{sessionId}")
    Integer nextSeq(@Param("sessionId") UUID sessionId);

    /**
     * 取当前会话尚未抽取的用户原话（游标之后），仅 USER 消息、限一批 30 条；
     * 不抽取模型自己的回答。返回的 Message 仅填充 id、seq、content。
     *
     * @param sessionId 会话 ID
     * @return 按 seq 正序的待抽取用户消息
     */
    @Select("SELECT id,seq,content FROM messages WHERE session_id=#{sessionId} AND role='USER' "
            + "AND seq>coalesce((SELECT last_seq FROM memory_extraction_cursor "
            + "WHERE session_id=#{sessionId}),0) ORDER BY seq LIMIT 30")
    List<Message> selectUnprocessedUserMessages(@Param("sessionId") UUID sessionId);
}
