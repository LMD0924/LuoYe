package com.luoye.service.chat.impl;

import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.ResultCode;
import com.luoye.service.chat.AssistantFactory;
import com.luoye.service.chat.ChatRun;
import com.luoye.service.chat.ChatService;
import com.luoye.service.chat.LuoyeAssistant;
import com.luoye.service.chat.PersistentChatMemoryStore;
import com.luoye.service.session.SessionService;
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
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 流式对话编排服务实现。
 */
@Service
public class ChatServiceImpl implements ChatService {

    // 键是会话 ID，值是占用该会话的任务；原子插入防止当前进程同时启动两轮生成。
    private final ConcurrentMap<UUID, ChatRun> runs = new ConcurrentHashMap<>();
    private final SessionService sessionService;
    private final AssistantFactory factory;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final int maxMessages;
    private final long timeoutSeconds;
    private final String persona;

    /** 注入生成线程池、超时调度器和 M1 对话参数。 */
    public ChatServiceImpl(
            SessionService sessionService,
            AssistantFactory factory,
            ExecutorService chatExecutor,
            ScheduledExecutorService chatScheduler,
            @Value("${luoye.chat.max-messages:20}") int maxMessages,
            @Value("${luoye.chat.stream-timeout-seconds:120}") long timeoutSeconds,
            @Value("${luoye.chat.persona:你是落叶，一位温暖、诚实、有独立判断的个人助手。用中文简洁回答，记不清时坦诚说明。}") String persona) {
        // 启动时拒绝过小窗口或无效超时；窗口按消息条数计数，不是按问答轮数。
        if (maxMessages < 4 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Invalid chat window/timeout");
        }
        this.sessionService = sessionService;
        this.factory = factory;
        this.executor = chatExecutor;
        this.scheduler = chatScheduler;
        this.maxMessages = maxMessages;
        this.timeoutSeconds = timeoutSeconds;
        this.persona = persona;
    }

    @Override
    public ChatRun reserve(UUID owner, UUID sessionId, String content) {
        if (content == null || content.isBlank() || content.length() > 20000) {
            throw new BusinessException(ResultCode.INVALID_CONTENT);
        }
        // 先校验归属再占位；查询不到或属于其他用户时统一按会话不存在处理。
        sessionService.requireSession(owner, sessionId);
        ChatRun run = new ChatRun(owner, sessionId, content);
        // 已有任务时保留原值并返回 409；不能用先查询再插入代替这一步原子操作。
        if (runs.putIfAbsent(sessionId, run) != null) {
            throw new BusinessException(ResultCode.SESSION_BUSY);
        }
        return run;
    }

    @Override
    public void start(ChatRun run, BiConsumer<String, Object> sink, Runnable completion) {
        // 锁住同一任务，使回调绑定、任务启动与断连清理不会交叉修改状态。
        synchronized (run) {
            if (run.ended) {
                // 可能在控制器注册断连回调后已经结束；直接关闭响应，不再启动模型。
                completion.run();
                return;
            }
            // 回调保存在 Run 中，后续由模型线程或超时线程调用。
            run.sink = sink;
            run.completion = completion;
            try {
                // 先把 runId 告诉前端，用户才能用该 ID 停止当前回复。
                sink.accept("start", Map.of("type", "start", "runId", run.id));
                // 从提交阶段开始计时，排队耗时也计入本轮总超时。
                run.deadline = scheduler.schedule(
                        () -> fail(run, "stream_timeout", "回复超时，请稍后重试"),
                        timeoutSeconds,
                        TimeUnit.SECONDS);
                // 生成初始化交给线程池，HTTP 请求线程无需等待完整回复。
                executor.execute(() -> generate(run));
            } catch (RejectedExecutionException e) {
                // 执行器已关闭或任务队列已满；统一发送错误并释放会话占位。
                fail(run, "server_busy", "服务繁忙，请稍后重试");
            } catch (RuntimeException e) {
                // 包括 start 事件发送失败；已建立的本地状态也必须清理。
                fail(run, "generation_failed", "无法启动回复");
            }
        }
    }

    /** 创建持久化 memory，启动 LangChain4j TokenStream，并转发令牌。 */
    private void generate(ChatRun run) {
        try {
            LuoyeAssistant assistant;
            synchronized (run) {
                if (run.ended) {
                    return;
                }
                // 从数据库加载最近消息；每轮独立的存储对象会记住本轮是否已写入。
                run.store = new PersistentChatMemoryStore(
                        sessionService, run.sessionId, run.id, maxMessages);
                assistant = factory.create(run.sessionId, run.store, maxMessages);
            }

            // 两个占位字符串分别填入长期记忆和知识库提示词；当前尚未接入检索。
            assistant.stream(
                            run.sessionId,
                            run.content(),
                            persona,
                            "本阶段未启用",
                            "本阶段未启用")
                    // token 是供应商返回的文本片段，可能包含多个字符；按原样转发给前端追加。
                    .onNext(token -> {
                        synchronized (run) {
                            // 超时或用户停止后忽略迟到片段，不再向已结束的连接写入。
                            if (!run.ended) {
                                try {
                                    run.sink.accept("delta", Map.of(
                                            "type", "delta",
                                            "content", token));
                                } catch (RuntimeException e) {
                                    // SSE 发送失败说明本轮无法继续交付；关闭本地回调和持久化入口。
                                    finish(run);
                                }
                            }
                        }
                    })
                    // LangChain4j 完成回复并更新 ChatMemory 后进入此回调；这里补记用量和完成事件。
                    .onComplete(response -> {
                        synchronized (run) {
                            if (run.ended) {
                                return;
                            }
                            try {
                                // 完整助手消息由 store 保存，使用其主键关联用量与前端显示。
                                UUID messageId = run.store.assistantMessageId();
                                if (messageId == null) {
                                    // 没有持久化主键就不能向前端宣告成功。
                                    throw new IllegalStateException("Assistant message not saved");
                                }
                                // 部分模型不报告用量，使用 null 表示未知，不伪造为 0。
                                Integer tokens = response.tokenUsage() == null
                                        ? null
                                        : response.tokenUsage().totalTokenCount();
                                sessionService.updateTokens(messageId, tokens);
                                // LinkedHashMap 允许 tokensUsed 为 null；Map.of 不接受空值。
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("type", "done");
                                data.put("runId", run.id);
                                data.put("messageId", messageId);
                                data.put("tokensUsed", tokens);
                                // done 只发送元数据，正文已通过前面的 delta 逐段传输。
                                run.sink.accept("done", data);
                            } catch (RuntimeException e) {
                                fail(run, "completion_failed", "回复保存失败，请刷新历史确认");
                            } finally {
                                // 无论补记用量或发送 done 是否成功，都释放本轮资源。
                                finish(run);
                            }
                        }
                    })
                    // 供应商异步失败统一转成可展示的错误码，不把底层异常细节发给用户。
                    .onError(error -> fail(
                            run,
                            "provider_unavailable",
                            "模型暂不可用，请检查模型配置或稍后重试"))
                    // 上面只注册处理器；start 才开始消费流式结果。
                    .start();
        } catch (Exception e) {
            // 初始化窗口、构建代理或启动流同步失败时，也走同一条清理路径。
            fail(run, "generation_failed", "无法生成回复，请检查服务配置后重试");
        }
    }

    /** 向客户端发送错误后关闭 Run；断开的客户端无法接收错误时直接清理。 */
    private void fail(ChatRun run, String code, String message) {
        synchronized (run) {
            if (run.ended) {
                return;
            }
            try {
                // 尚未绑定传输回调也允许结束；已经断开的连接则可能在发送时抛异常。
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

    @Override
    public void disconnect(ChatRun run) {
        synchronized (run) {
            finish(run);
        }
    }

    @Override
    public boolean abort(UUID owner, UUID sessionId, UUID runId) {
        sessionService.requireSession(owner, sessionId);
        // 同时核对任务 ID 与用户，防止旧页面的停止请求误停新一轮回复。
        ChatRun run = runs.get(sessionId);
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

    /** 释放超时任务、memory 和会话占位；调用方必须持有 run 锁，本方法不取消供应商网络请求。 */
    private void finish(ChatRun run) {
        if (run.ended) {
            return;
        }
        // 先标记终止；随后 complete 可能再次触发 disconnect，重入时会直接返回。
        run.ended = true;
        if (run.store != null) {
            // 拒绝迟到的记忆更新，避免停止之后又保存完整回复。
            run.store.close();
        }
        if (run.deadline != null) {
            // 取消尚未执行的超时任务；false 表示不打断已经开始执行的线程。
            run.deadline.cancel(false);
        }
        // 仅移除仍映射到当前 Run 的条目，不误删可能已替换的新任务。
        runs.remove(run.sessionId, run);
        if (run.completion != null) {
            run.completion.run();
        }
    }
}
