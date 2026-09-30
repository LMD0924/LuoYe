package com.luoye.service.session;

import com.luoye.dao.entity.Message;
import com.luoye.dao.entity.Session;

import java.util.List;
import java.util.UUID;

/**
 * 会话服务。
 *
 * <p>负责会话与消息的业务规则、归属校验和事务性写入。
 */
public interface SessionService {

    /** @return 用户的会话列表，最近活跃在前 */
    List<Session> listSessions(UUID userId);

    /** 创建空会话。 @param title 可空 @return 含数据库默认字段的会话 */
    Session createSession(UUID userId, String title);

    /**
     * 校验会话归属并返回会话。
     *
     * @throws com.luoye.common.exception.BusinessException 不存在或归属不符（404）
     */
    Session requireSession(UUID userId, UUID sessionId);

    /** @return 会话完整历史，按 seq 正序 */
    List<Message> history(UUID userId, UUID sessionId);

    /** @return 会话最近窗口（自然语序），供模型恢复上下文 */
    List<Message> recent(UUID sessionId, int limit);

    /**
     * 在事务中追加消息并分配会话内序号，同时更新会话活跃时间。
     *
     * @return 新消息 ID
     */
    UUID appendMessage(UUID sessionId, UUID runId, String role, String content);

    /** 补记 token 用量；tokens 为 null 表示供应商未报告，不修改。 */
    void updateTokens(UUID messageId, Integer tokens);

    /** 写入本轮检索日志与引用。 */
    void updateRetrieval(UUID messageId, String retrievalLog, String citations);
}
