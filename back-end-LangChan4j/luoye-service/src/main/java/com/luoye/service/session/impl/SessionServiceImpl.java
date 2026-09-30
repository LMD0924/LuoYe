package com.luoye.service.session.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.ResultCode;
import com.luoye.dao.entity.Message;
import com.luoye.dao.entity.Session;
import com.luoye.dao.mapper.MessageMapper;
import com.luoye.dao.mapper.SessionMapper;
import com.luoye.service.session.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 会话服务实现。
 */
@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;

    @Override
    public List<Session> listSessions(UUID userId) {
        // coalesce 让新建但未发言的会话也能按创建时间参与排序；软删除项排除。
        QueryWrapper<Session> wrapper = new QueryWrapper<Session>()
                .eq("user_id", userId)
                .isNull("deleted_at")
                .orderByDesc("coalesce(updated_at,created_at)");
        return sessionMapper.selectList(wrapper);
    }

    @Override
    public Session createSession(UUID userId, String title) {
        Session session = new Session();
        session.setUserId(userId);
        session.setTitle(title);
        session.setStatus("active");
        // 主键由 MP 分配；created_at 由数据库默认生成。
        sessionMapper.insert(session);
        // 回查取得数据库生成的 created_at 等字段。
        return sessionMapper.selectById(session.getId());
    }

    @Override
    public Session requireSession(UUID userId, UUID sessionId) {
        Session session = sessionMapper.selectOne(new QueryWrapper<Session>()
                .eq("id", sessionId)
                .eq("user_id", userId)
                .isNull("deleted_at"));
        if (session == null) {
            throw new BusinessException(ResultCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    @Override
    public List<Message> history(UUID userId, UUID sessionId) {
        // 先校验归属，再允许读取完整历史。
        requireSession(userId, sessionId);
        return messageMapper.selectList(new QueryWrapper<Message>()
                .eq("session_id", sessionId)
                .orderByAsc("seq"));
    }

    @Override
    public List<Message> recent(UUID sessionId, int limit) {
        List<Message> messages = messageMapper.selectRecent(sessionId, limit);
        // SQL 倒序取窗口，交给模型前恢复自然对话顺序。
        Collections.reverse(messages);
        return messages;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UUID appendMessage(UUID sessionId, UUID runId, String role, String content) {
        // 行锁持续到事务结束，串行化同一会话的序号分配；事务不包含模型网络请求。
        sessionMapper.selectForUpdate(sessionId);
        int seq = messageMapper.nextSeq(sessionId);

        Message message = new Message();
        message.setSessionId(sessionId);
        message.setRunId(runId);
        message.setSeq(seq);
        message.setRole(role);
        message.setContent(content);
        message.setStatus("completed");
        messageMapper.insert(message);

        // 更新会话活跃时间；MP updateById 只更新非空字段，不影响其他列。
        Session update = new Session();
        update.setId(sessionId);
        update.setUpdatedAt(Instant.now());
        sessionMapper.updateById(update);
        return message.getId();
    }

    @Override
    public void updateTokens(UUID messageId, Integer tokens) {
        // tokens 为 null 时 MP 默认不更新该字段，正好表示供应商未报告。
        Message update = new Message();
        update.setId(messageId);
        update.setTokensUsed(tokens);
        messageMapper.updateById(update);
    }

    @Override
    public void updateRetrieval(UUID messageId, String retrievalLog, String citations) {
        messageMapper.updateRetrieval(messageId, retrievalLog, citations);
    }
}
