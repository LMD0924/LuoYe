package com.luoye.service.chat;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 流式对话编排服务。
 *
 * <p>负责生成槽位、超时、模型回调和 SSE 事件之间的协调。
 * 当前服务实例内，每个会话同一时间只允许一条 Run；此约束不跨多个后端实例。
 */
public interface ChatService {

    /**
     * 预留会话生成槽位；此时尚未调用模型，也尚未保存本轮消息。
     *
     * @param owner 从 JWT 身份读取的用户 UUID
     * @param sessionId 用户指定的会话 UUID
     * @param content 原始消息，须非空白且不超过 20000 字符
     * @return 已登记的任务，供 start、disconnect 或 abort 使用
     * @throws com.luoye.common.exception.BusinessException 消息非法、会话不存在或会话已有生成任务
     */
    ChatRun reserve(UUID owner, UUID sessionId, String content);

    /**
     * 启动异步生成；正常返回只表示任务已提交，不代表回复完成。
     *
     * @param run reserve 返回的任务
     * @param sink 向控制器传递事件名与数据的回调，传输失败时可抛出异常
     * @param completion 关闭 SSE 响应的回调，统一在任务结束时执行
     */
    void start(ChatRun run, BiConsumer<String, Object> sink, Runnable completion);

    /** 客户端断开或 SSE 超时后的清理入口；重复调用安全。 */
    void disconnect(ChatRun run);

    /**
     * 停止指定流式任务。
     *
     * <p>关闭本地任务和存储回调；当前没有调用供应商底层的网络取消接口。
     *
     * @param owner 当前用户 UUID
     * @param sessionId 会话 UUID
     * @param runId 指定生成任务 UUID
     * @return 任务存在且成功进入停止流程时返回 true
     */
    boolean abort(UUID owner, UUID sessionId, UUID runId);
}
