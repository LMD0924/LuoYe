package com.luoye.service.chat;

import com.luoye.common.ApiException;
import com.luoye.dao.repository.ChatRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * M1 流式对话编排服务。
 *
 * <p>负责生成槽位、超时、模型回调和 SSE 事件之间的协调。
 * 每个会话同一时间只允许一条 Run，避免消息序号和 ChatMemory 同时写入。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Service
public class ChatService {

    /** 一次用户消息到模型完成或失败的生命周期对象。 */
    public final class Run {

        public final UUID id = UUID.randomUUID();
        public final UUID owner;
        public final UUID sessionId;

        private final String content;
        private PersistentChatMemoryStore store;
        private boolean ended;
        private BiConsumer<String, Object> sink;
        private Runnable completion;
        private ScheduledFuture<?> deadline;

        private Run(UUID owner, UUID sessionId, String content) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.content = content;
        }

        /** @return 当前 Run 是否已经关闭，供异步回调检查竞态 */
        public synchronized boolean ended() {
            return ended;
        }
    }

    private final ConcurrentMap<UUID, Run> runs = new ConcurrentHashMap<>();
    private final ChatRepository repository;
    private final AssistantFactory factory;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final int maxMessages;
    private final long timeoutSeconds;
    private final String persona;

    /** 注入生成线程池、超时调度器和 M1 对话参数。 */
    public ChatService(
            ChatRepository repository,
            AssistantFactory factory,
            ExecutorService chatExecutor,
            ScheduledExecutorService chatScheduler,
            @Value("\u0024{luoye.chat.max-messages:20}") int maxMessages,
            @Value("\u0024{luoye.chat.stream-timeout-seconds:120}") long timeoutSeconds,
            @Value("\u0024{luoye.chat.persona:你是落叶，一位温暖、诚实、有独立判断的个人助手。用中文简洁回答，记不清时坦诚说明。}") String persona) {
        if (maxMessages < 4 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Invalid chat window/timeout");
        }
        this.repository = repository;
        this.factory = factory;
        this.executor = chatExecutor;
        this.scheduler = chatScheduler;
        this.maxMessages = maxMessages;
        this.timeoutSeconds = timeoutSeconds;
        this.persona = persona;
    }

    /**
     * 预留会话生成槽位。
     *
     * @throws ApiException 消息非法、会话不存在或会话已有生成任务
     */
    public Run reserve(UUID owner, UUID sessionId, String content) {
        if (content == null || content.isBlank() || content.length() > 20000) {
            throw new ApiException(400, "invalid_content", "消息不能为空且不能超过 20000 字符");
        }
        repository.requireSession(owner, sessionId);
        Run run = new Run(owner, sessionId, content);
        if (runs.putIfAbsent(sessionId, run) != null) {
            throw new ApiException(409, "session_busy", "该会话正在生成，请等待或停止当前回复");
        }
        return run;
    }

    /** 启动异步模型调用，并把事件交给 SSE sink。 */
    public void start(Run run, BiConsumer<String, Object> sink, Runnable completion) {
        synchronized (run) {
            if (run.ended) {
                completion.run();
                return;
            }
            run.sink = sink;
            run.completion = completion;
            try {
                sink.accept("start", Map.of("type", "start", "runId", run.id));
                run.deadline = scheduler.schedule(
                        () -> fail(run, "stream_timeout", "回复超时，请稍后重试"),
                        timeoutSeconds,
                        TimeUnit.SECONDS);
                executor.execute(() -> generate(run));
            } catch (RejectedExecutionException e) {
                fail(run, "server_busy", "服务繁忙，请稍后重试");
            } catch (RuntimeException e) {
                fail(run, "generation_failed", "无法启动回复");
            }
        }
    }

    /** 创建持久化 memory，启动 LangChain4j TokenStream，并转发令牌。 */
    private void generate(Run run) {
        try {
            LuoyeAssistant assistant;
            synchronized (run) {
                if (run.ended) {
                    return;
                }
                run.store = new PersistentChatMemoryStore(
                        repository, run.sessionId, run.id, maxMessages);
                assistant = factory.create(run.sessionId, run.store, maxMessages);
            }

            assistant.stream(
                            run.sessionId,
                            run.content,
                            persona,
                            "本阶段未启用",
                            "本阶段未启用")
                    .onNext(token -> {
                        synchronized (run) {
                            if (!run.ended) {
                                try {
                                    run.sink.accept("delta", Map.of(
                                            "type", "delta",
                                            "content", token));
                                } catch (RuntimeException e) {
                                    finish(run);
                                }
                            }
                        }
                    })
                    .onComplete(response -> {
                        synchronized (run) {
                            if (run.ended) {
                                return;
                            }
                            try {
                                UUID messageId = run.store.assistantMessageId();
                                if (messageId == null) {
                                    throw new IllegalStateException("Assistant message not saved");
                                }
                                Integer tokens = response.tokenUsage() == null
                                        ? null
                                        : response.tokenUsage().totalTokenCount();
                                repository.tokens(messageId, tokens);
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("type", "done");
                                data.put("runId", run.id);
                                data.put("messageId", messageId);
                                data.put("tokensUsed", tokens);
                                run.sink.accept("done", data);
                            } catch (RuntimeException e) {
                                fail(run, "completion_failed", "回复保存失败，请刷新历史确认");
                            } finally {
                                finish(run);
                            }
                        }
                    })
                    .onError(error -> fail(
                            run,
                            "provider_unavailable",
                            "模型暂不可用，请检查模型配置或稍后重试"))
                    .start();
        } catch (Exception e) {
            fail(run, "generation_failed", "无法生成回复，请检查服务配置后重试");
        }
    }

    /** 向客户端发送错误后关闭 Run；断开的客户端无法接收错误时直接清理。 */
    private void fail(Run run, String code, String message) {
        synchronized (run) {
            if (run.ended) {
                return;
            }
            try {
                if (run.sink != null) {
                    run.sink.accept("error", Map.of(
                            "type", "error",
                            "code", code,
                            "message", message));
                }
            } catch (RuntimeException ignored) {
                // 客户端已断开时，错误帧无法发送，但服务端状态仍必须释放。
            } finally {
                finish(run);
            }
        }
    }

    /** 客户端断开或 SSE 超时后的清理入口。 */
    public void disconnect(Run run) {
        synchronized (run) {
            finish(run);
        }
    }

    /**
     * 停止指定流式任务。
     *
     * <p>关闭本地任务和存储回调；当前没有调用供应商底层的网络取消接口。
     *
     * @param owner 当前用户 UUID
     * @param sessionId 会话 UUID
     * @param runId 指定生成任务 UUID
     *
     * @return 任务存在且成功进入停止流程时返回 true
     */
    public boolean abort(UUID owner, UUID sessionId, UUID runId) {
        repository.requireSession(owner, sessionId);
        Run run = runs.get(sessionId);
        if (run == null || !run.id.equals(runId) || !run.owner.equals(owner)) {
            return false;
        }
        synchronized (run) {
            if (run.ended) {
                return false;
            }
            fail(run, "aborted", "已停止生成");
            return true;
        }
    }

    /** 释放超时任务、memory 和会话生成槽位。 */
    private void finish(Run run) {
        if (run.ended) {
            return;
        }
        run.ended = true;
        if (run.store != null) {
            run.store.close();
        }
        if (run.deadline != null) {
            run.deadline.cancel(false);
        }
        runs.remove(run.sessionId, run);
        if (run.completion != null) {
            run.completion.run();
        }
    }
}