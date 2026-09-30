package com.luoye.service.chat;

import com.luoye.dao.entity.Message;
import com.luoye.service.session.SessionService;
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
 * <p>每次生成创建一个实例：构造时通过 {@link SessionService} 恢复最近窗口，
 * 消息新增时追加到数据库。MessageWindowChatMemory 的窗口淘汰只影响模型上下文，
 * 不会删除历史记录。
 */
public final class PersistentChatMemoryStore implements ChatMemoryStore {

    private final SessionService sessionService;
    private final UUID sessionId;
    private final UUID runId;
    // window 只服务本轮模型上下文，数据库保存的历史不会随窗口淘汰而删除。
    private List<ChatMessage> window;
    // 两个保存标记避免框架多次更新窗口时重复插入；closed 阻止结束后的迟到写入。
    private boolean userSaved;
    private boolean closed;
    private UUID assistantMessageId;

    /**
     * 从数据库恢复指定会话的最近消息。
     *
     * @param sessionService 会话服务
     * @param sessionId 当前会话 ID
     * @param runId 当前流式生成 ID
     * @param maxMessages 本轮送入模型的最大消息数
     */
    public PersistentChatMemoryStore(
            SessionService sessionService,
            UUID sessionId,
            UUID runId,
            int maxMessages) {
        this.sessionService = sessionService;
        this.sessionId = sessionId;
        this.runId = runId;
        this.window = new ArrayList<>();

        // recent 已限定 completed 的用户/助手消息并恢复 seq 正序，按原顺序装入模型窗口。
        for (Message m : sessionService.recent(sessionId, maxMessages)) {
            // M1 只有用户消息和助手消息进入模型窗口，工具/系统消息留待后续里程碑。
            window.add("USER".equals(m.getRole())
                    ? UserMessage.from(m.getContent())
                    : AiMessage.from(m.getContent()));
        }
    }

    /** 校验框架传入的 memory ID，防止把其他会话的消息写入当前存储。 */
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
        // synchronized 与 close 使用同一对象锁，保证关闭标记和保存操作有确定先后顺序。
        if (closed) {
            return;
        }

        if (!messages.isEmpty()) {
            // 框架提交的是整个新窗口；只检查末尾的增量消息，不把历史逐条重新插入。
            ChatMessage last = messages.get(messages.size() - 1);
            if (last instanceof UserMessage u && !userSaved) {
                sessionService.appendMessage(sessionId, runId, "USER", u.singleText());
                // 仅在数据库追加成功后置位；写入失败时异常交回生成流程处理。
                userSaved = true;
            } else if (last instanceof AiMessage a
                    && userSaved
                    && assistantMessageId == null) {
                // 用户消息已保存且本轮还没有助手主键时，才接收非空的完整助手回答。
                if (a.text() == null || a.text().isBlank()) {
                    throw new IllegalStateException("Empty model response");
                }
                // 主键交给 ChatService 的完成回调，用来补记用量并发送 done。
                assistantMessageId = sessionService.appendMessage(
                        sessionId, runId, "ASSISTANT", a.text());
            }
        }
        // 保存窗口副本，包括框架维护的系统消息；不会持有调用方可继续修改的列表。
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
