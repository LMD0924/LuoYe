package com.luoye.service.chat.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.ResultCode;
import com.luoye.dao.entity.Todo;
import com.luoye.service.chat.AssistantFactory;
import com.luoye.service.chat.ChatRun;
import com.luoye.service.chat.ChatService;
import com.luoye.service.chat.LuoyeAssistant;
import com.luoye.service.chat.PersistentChatMemoryStore;
import com.luoye.service.config.UserSettingsService;
import com.luoye.service.kb.KnowledgeService;
import com.luoye.service.memory.MemoryService;
import com.luoye.service.session.SessionService;
import com.luoye.service.support.RequestContext;
import com.luoye.service.todo.TodoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 流式对话编排：召回记忆/知识库、绑定工具、结束后抽取记忆。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private final ConcurrentMap<UUID, ChatRun> runs = new ConcurrentHashMap<>();
    private final SessionService sessionService;
    private final AssistantFactory factory;
    private final MemoryService memory;
    private final KnowledgeService knowledge;
    private final UserSettingsService settings;
    private final TodoService todos;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final int maxMessages;
    private final long timeoutSeconds;
    private final String fallbackPersona;

    public ChatServiceImpl(
            SessionService sessionService,
            AssistantFactory factory,
            MemoryService memory,
            KnowledgeService knowledge,
            UserSettingsService settings,
            TodoService todos,
            ObjectMapper mapper,
            ExecutorService chatExecutor,
            ScheduledExecutorService chatScheduler,
            @Value("${luoye.chat.max-messages:20}") int maxMessages,
            @Value("${luoye.chat.stream-timeout-seconds:120}") long timeoutSeconds,
            @Value("${luoye.chat.persona:你是落叶，一位温暖、诚实、有独立判断的个人助手。用中文简洁回答，记不清时坦诚说明。}")
            String fallbackPersona) {
        if (maxMessages < 4 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Invalid chat window/timeout");
        }
        this.sessionService = sessionService;
        this.factory = factory;
        this.memory = memory;
        this.knowledge = knowledge;
        this.settings = settings;
        this.todos = todos;
        this.mapper = mapper;
        this.executor = chatExecutor;
        this.scheduler = chatScheduler;
        this.maxMessages = maxMessages;
        this.timeoutSeconds = timeoutSeconds;
        this.fallbackPersona = fallbackPersona;
    }

    @Override
    public ChatRun reserve(UUID owner, UUID sessionId, String content) {
        if (content == null || content.isBlank() || content.length() > 20000) {
            throw new BusinessException(ResultCode.INVALID_CONTENT);
        }
        sessionService.requireSession(owner, sessionId);
        ChatRun run = new ChatRun(owner, sessionId, content);
        if (runs.putIfAbsent(sessionId, run) != null) {
            throw new BusinessException(ResultCode.SESSION_BUSY);
        }
        return run;
    }

    @Override
    public void start(ChatRun run, BiConsumer<String, Object> sink, Runnable completion) {
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

    private void generate(ChatRun run) {
        try {
            LuoyeAssistant assistant;
            MemoryService.Recall memoryRecall = MemoryService.Recall.empty();
            KnowledgeService.Recall kbRecall = KnowledgeService.Recall.empty();
            synchronized (run) {
                if (run.ended) {
                    return;
                }
                RequestContext.bind(run.sessionId, run.owner);
                boolean memoryOn = settings.memoryEnabled(run.owner);
                if (memoryOn) {
                    memoryRecall = memory.recall(run.owner, run.content());
                }
                kbRecall = knowledge.retrieve(run.owner, run.content());
                run.store = new PersistentChatMemoryStore(
                        sessionService, run.sessionId, run.id, maxMessages);
                assistant = factory.create(run.sessionId, run.store, maxMessages);
            }

            String persona = buildPersona(run.owner);
            assistant.stream(
                            run.sessionId,
                            run.content(),
                            persona,
                            memoryRecall.context(),
                            kbRecall.context() + reminderBlock(run.owner))
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
                                sessionService.updateTokens(messageId, tokens);
                                sessionService.updateRetrieval(
                                        messageId,
                                        json(Map.of("memory", memoryRecall.log(), "kb", kbRecall.log())),
                                        json(mergeCitations(memoryRecall.citations(), kbRecall.citations())));
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("type", "done");
                                data.put("runId", run.id);
                                data.put("messageId", messageId);
                                data.put("tokensUsed", tokens);
                                data.put("citations", mergeCitations(
                                        memoryRecall.citations(), kbRecall.citations()));
                                run.sink.accept("done", data);
                                UUID owner = run.owner;
                                UUID sessionId = run.sessionId;
                                String userText = run.content();
                                if (settings.memoryEnabled(owner)) {
                                    executor.execute(() -> memory.extractAfterTurn(owner, sessionId, userText));
                                }
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

    private String buildPersona(UUID owner) {
        String persona = settings.persona(owner);
        if (persona == null || persona.isBlank()) {
            persona = fallbackPersona;
        }
        double companion = settings.companion(owner);
        String tone = companion >= 0.7
                ? "当前陪伴浓度较高：语气更口语、更有温度，但仍保持边界。"
                : companion <= 0.3
                ? "当前偏理性工具模式：简洁、克制情感色彩。"
                : "当前为均衡陪伴：温和、清楚、不装成真人。";
        return persona + "\n" + tone
                + "\n你可以调用工具记录记忆、笔记、待办；仅在用户开启联网时搜索网络。"
                + "引用知识库时使用 [n] 编号。不确定就说明不确定。";
    }

    private String reminderBlock(UUID owner) {
        List<Todo> due = todos.dueReminders(owner);
        if (due.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("\n到期提醒：\n");
        for (Todo todo : due) {
            builder.append("- ").append(todo.getTitle()).append('\n');
        }
        return builder.toString();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "null";
        }
    }

    private static List<Map<String, Object>> mergeCitations(
            List<Map<String, Object>> a, List<Map<String, Object>> b) {
        List<Map<String, Object>> all = new ArrayList<>();
        if (a != null) {
            all.addAll(a);
        }
        if (b != null) {
            all.addAll(b);
        }
        return all;
    }

    private void fail(ChatRun run, String code, String message) {
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
                /* 客户端已断开。 */
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

    private void finish(ChatRun run) {
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
        RequestContext.unbind(run.sessionId);
        if (run.completion != null) {
            run.completion.run();
        }
    }
}
