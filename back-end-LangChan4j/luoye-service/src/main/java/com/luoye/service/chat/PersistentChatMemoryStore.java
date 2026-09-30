package com.luoye.service.chat;

import com.luoye.dao.repository.ChatRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于 PostgreSQL messages 表的短期记忆存储。
 *
 * <p>每次生成创建一个实例：构造时恢复最近窗口，消息新增时追加到数据库。
 * MessageWindowChatMemory 的窗口淘汰只影响模型上下文，不会删除历史记录。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
public final class PersistentChatMemoryStore implements ChatMemoryStore {

    private final ChatRepository repository;
    private final UUID sessionId;
    private final UUID runId;
    private List<ChatMessage> window;
    private boolean userSaved;
    private boolean closed;
    private UUID assistantMessageId;

    /**
     * 从数据库恢复指定会话的最近消息。
     *
     * @param repository 消息仓储
     * @param sessionId 当前会话 ID
     * @param runId 当前流式生成 ID
     * @param maxMessages 本轮送入模型的最大消息数
     */
    public PersistentChatMemoryStore(
            ChatRepository repository,
            UUID sessionId,
            UUID runId,
            int maxMessages) {
        this.repository = repository;
        this.sessionId = sessionId;
        this.runId = runId;
        this.window = new ArrayList<>();

        for (var m : repository.recent(sessionId, maxMessages)) {
            // M1 只有用户消息和助手消息进入模型窗口，工具/系统消息留待后续里程碑。
            window.add(m.role().equals("USER") ? UserMessage.from(m.content()) : AiMessage.from(m.content()));
        }
    }

    private void check(Object id) {
        if (!sessionId.equals(id)) {
            throw new IllegalArgumentException("Unexpected memory id");
        }
    }

    /**
     * 返回当前会话的模型上下文窗口。
     *
     * @param id LangChain4j memory ID
     * @return 上下文副本，避免调用方直接修改内部状态
     */
    @Override
    public synchronized List<ChatMessage> getMessages(Object id) {
        check(id);
        return new ArrayList<>(window);
    }

    /**
     * 接收 LangChain4j 更新后的窗口，并将新的一轮消息持久化。
     *
     * <p>只识别窗口末尾的新用户消息和助手消息，避免恢复历史时重复写入。
     *
     * @param id LangChain4j memory ID
     * @param messages 更新后的窗口
     */
    @Override
    public synchronized void updateMessages(Object id, List<ChatMessage> messages) {
        check(id);
        if (closed) {
            return;
        }

        if (!messages.isEmpty()) {
            ChatMessage last = messages.get(messages.size() - 1);
            if (last instanceof UserMessage u && !userSaved) {
                repository.append(sessionId, runId, "USER", u.singleText());
                userSaved = true;
            } else if (last instanceof AiMessage a
                    && userSaved
                    && assistantMessageId == null) {
                if (a.text() == null || a.text().isBlank()) {
                    throw new IllegalStateException("Empty model response");
                }
                assistantMessageId = repository.append(
                        sessionId, runId, "ASSISTANT", a.text());
            }
        }
        window = new ArrayList<>(messages);
    }

    /**
     * 清空当前模型窗口。
     *
     * <p>这里只清空内存窗口，不删除数据库历史，数据删除由独立业务接口负责。
     *
     * @param id LangChain4j memory ID
     */
    @Override
    public synchronized void deleteMessages(Object id) {
        check(id);
        window.clear();
    }

    /** 标记当前生成结束，忽略后续迟到的 memory 回调。 */
    public synchronized void close() {
        closed = true;
    }

    /** @return 已持久化的助手消息 ID，尚未保存时返回 null */
    public synchronized UUID assistantMessageId() {
        return assistantMessageId;
    }
}