package com.luoye.service.chat;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BiConsumer;

/**
 * 一次用户消息到模型完成或失败的生命周期对象（Run）。
 *
 * <p>id 区分同一会话的不同轮生成；owner 和 sessionId 用于停止请求的归属校验。
 * 所有可变状态的读写都由对象锁保护，避免完成、超时、断连同时收尾。
 * 协作字段对同模块的 impl 子包公开。
 */
public class ChatRun {

    /** 本轮生成 ID，前端据此停止当前回复。 */
    public final UUID id = UUID.randomUUID();

    /** 发起用户 ID。 */
    public final UUID owner;

    /** 目标会话 ID。 */
    public final UUID sessionId;

    /** 本轮原始输入。 */
    private final String content;

    /** 任务真正执行时创建，用于恢复与保存上下文。 */
    public PersistentChatMemoryStore store;

    /** 终止标记。 */
    public boolean ended;

    /** 事件出口：接收事件名和 JSON 数据。 */
    public BiConsumer<String, Object> sink;

    /** 关闭 HTTP 响应的回调。 */
    public Runnable completion;

    /** 可取消的超时任务。 */
    public ScheduledFuture<?> deadline;

    public ChatRun(UUID owner, UUID sessionId, String content) {
        this.owner = owner;
        this.sessionId = sessionId;
        this.content = content;
    }

    /** @return 当前 Run 是否已经关闭，供异步回调检查竞态 */
    public synchronized boolean ended() {
        return ended;
    }

    /** @return 本轮原始输入 */
    public String content() {
        return content;
    }
}
