# M1 中文注释与格式化代码

本文件为 2026-09-30 交付快照，完整收录本次整理的源代码。应用源文件仍是后续修改的准据。按 codex-format-comment skill 补充中文职责、参数、返回值和关键边界注释，并统一格式；未增加功能。

## back-end-LangChan4j/luoye-common/src/main/java/com/luoye/common/ApiException.java

```java
package com.luoye.common;

/**
 * 业务 API 异常。
 *
 * <p>携带 HTTP 状态码和稳定错误码，由 API 层统一转换为 JSON 错误响应。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    /**
     * 创建业务 API 异常。
     *
     * @param status HTTP 状态码
     * @param code 面向客户端的稳定错误码
     * @param message 面向用户的错误信息
     */
    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** @return HTTP 状态码 */
    public int status() {
        return status;
    }

    /** @return 稳定错误码 */
    public String code() {
        return code;
    }
}
```

## back-end-LangChan4j/luoye-dao/src/main/java/com/luoye/dao/repository/ChatRepository.java

```java
package com.luoye.dao.repository;

import com.luoye.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * M1 对话数据访问仓储。
 *
 * <p>使用 JdbcTemplate 读取 Flyway 管理的 sessions、messages 和 users 表，
 * 避免在 MVP 阶段引入尚未稳定的 JPA 实体映射。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Repository
public class ChatRepository {

    /** 会话信息投影；updatedAt 在尚未更新时可以为空。 */
    public record Session(UUID id, String title, Instant createdAt, Instant updatedAt) {
    }

    /** 历史消息投影；seq 为会话内顺序，runId 为生成任务 UUID。 */
    public record Message(
            UUID id,
            UUID sessionId,
            int seq,
            String role,
            String content,
            String status,
            UUID runId,
            Instant createdAt) {
    }

    /** 登录校验使用的账号投影；passwordHash 不作为接口响应。 */
    public record Account(UUID id, String username, String passwordHash) {
    }

    private static final RowMapper<Session> SESSION = (r, n) -> new Session(
            r.getObject("id", UUID.class),
            r.getString("title"),
            r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("updated_at") == null
                    ? null
                    : r.getTimestamp("updated_at").toInstant());

    private static final RowMapper<Message> MESSAGE = (r, n) -> new Message(
            r.getObject("id", UUID.class),
            r.getObject("session_id", UUID.class),
            r.getInt("seq"),
            r.getString("role"),
            r.getString("content"),
            r.getString("status"),
            r.getObject("run_id", UUID.class),
            r.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    /**
     * 构造仓储。
     *
     * @param jdbc Spring JDBC 模板
     * @param tx 用于原子分配会话内消息序号
     */
    public ChatRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** @param username 登录名 @return 账号，不存在时为空 */
    public Optional<Account> account(String username) {
        return jdbc.query(
                        "SELECT id,username,password_hash FROM users "
                                + "WHERE username=? AND deleted_at IS NULL",
                        (r, n) -> new Account(
                                r.getObject("id", UUID.class),
                                r.getString("username"),
                                r.getString("password_hash")),
                        username)
                .stream()
                .findFirst();
    }

    /** 创建首个单用户账号；重复账号由数据库唯一约束保护。 */
    public void createAccount(String username, String hash) {
        jdbc.update(
                "INSERT INTO users(id,username,password_hash) VALUES (?,?,?)",
                UUID.randomUUID(),
                username,
                hash);
    }

    /** 校验会话属于当前用户，避免通过 UUID 越权访问。 */
    public Session requireSession(UUID owner, UUID id) {
        return jdbc.query(
                        "SELECT * FROM sessions "
                                + "WHERE id=? AND user_id=? AND deleted_at IS NULL",
                        SESSION,
                        id,
                        owner)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "session_not_found", "会话不存在"));
    }

    /** @return 当前用户的会话列表，最近更新的会话排在前面 */
    public List<Session> sessions(UUID owner) {
        return jdbc.query(
                "SELECT * FROM sessions WHERE user_id=? AND deleted_at IS NULL "
                        + "ORDER BY coalesce(updated_at,created_at) DESC",
                SESSION,
                owner);
    }

    /** 创建空会话。 */
    public Session createSession(UUID owner, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sessions(id,user_id,title) VALUES (?,?,?)", id, owner, title);
        return requireSession(owner, id);
    }

    /** @return 会话完整历史，供前端切换会话时恢复显示 */
    public List<Message> history(UUID owner, UUID id) {
        requireSession(owner, id);
        return jdbc.query(
                "SELECT * FROM messages WHERE session_id=? ORDER BY seq",
                MESSAGE,
                id);
    }

    /** @return 最近的已完成用户/助手消息，按时间正序供模型使用 */
    public List<Message> recent(UUID sessionId, int limit) {
        List<Message> out = jdbc.query(
                "SELECT * FROM messages WHERE session_id=? "
                        + "AND role IN ('USER','ASSISTANT') AND status='completed' "
                        + "ORDER BY seq DESC LIMIT ?",
                MESSAGE,
                sessionId,
                limit);
        // SQL 倒序取最近窗口，交给模型前恢复为自然的对话顺序。
        Collections.reverse(out);
        return out;
    }

    /**
     * 追加消息并分配会话内序号。
     *
     * <p>锁住会话行后计算 seq，保证同一会话的并发请求不会得到重复序号。
     */
    public UUID append(UUID sessionId, UUID runId, String role, String content) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM sessions WHERE id=? FOR UPDATE", UUID.class, sessionId);
            int seq = jdbc.queryForObject(
                    "SELECT coalesce(max(seq),0)+1 FROM messages WHERE session_id=?",
                    Integer.class,
                    sessionId);
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO messages(id,session_id,seq,role,content,status,run_id) "
                            + "VALUES (?,?,?,?,?,'completed',?)",
                    id,
                    sessionId,
                    seq,
                    role,
                    content,
                    runId);
            jdbc.update("UPDATE sessions SET updated_at=now() WHERE id=?", sessionId);
            return id;
        });
    }

    /** 保存模型返回的 token 使用量。 */
    public void tokens(UUID messageId, Integer count) {
        jdbc.update("UPDATE messages SET tokens_used=? WHERE id=?", count, messageId);
    }
}
```

## back-end-LangChan4j/luoye-service/src/main/java/com/luoye/service/chat/LuoyeAssistant.java

```java
package com.luoye.service.chat;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.UUID;

/**
 * 落叶对话编排接口。
 *
 * <p>由 LangChain4j 在运行时生成实现。{@code sessionId} 绑定短期记忆窗口，
 * {@code TokenStream} 将模型输出逐段交给 SSE 层。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
public interface LuoyeAssistant {

    /**
     * 流式生成一轮助手回复。
     *
     * @param sessionId 会话 ID，同时作为 LangChain4j 的 memory ID
     * @param userMessage 当前用户消息
     * @param persona 当前人格提示词
     * @param memoryContext 长期记忆上下文占位；M1 暂未启用
     * @param kbContext 知识库上下文占位；M1 暂未启用
     * @return LangChain4j 流式令牌管道
     */
    @SystemMessage("{{persona}}\n当前仅使用本会话短期记忆。不要声称拥有长期记忆、联网或工具能力。\n记忆摘要：{{memoryContext}}\n知识库上下文：{{kbContext}}")
    TokenStream stream(
            @MemoryId UUID sessionId,
            @UserMessage String userMessage,
            @V("persona") String persona,
            @V("memoryContext") String memoryContext,
            @V("kbContext") String kbContext);
}
```

## back-end-LangChan4j/luoye-service/src/main/java/com/luoye/service/chat/PersistentChatMemoryStore.java

```java
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
```

## back-end-LangChan4j/luoye-service/src/main/java/com/luoye/service/chat/AssistantFactory.java

```java
package com.luoye.service.chat;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 创建绑定会话短期记忆的 LangChain4j Assistant。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Component
public class AssistantFactory {

    private final StreamingChatLanguageModel model;

    /**
     * 构造器注入流式模型。
     *
     * @param model 已由 API 模块按供应商配置创建的模型
     */
    public AssistantFactory(StreamingChatLanguageModel model) {
        this.model = model;
    }

    /**
     * 为一轮生成创建隔离的 ChatMemory 和 Assistant。
     *
     * @param id 会话 memory ID
     * @param store 持久化窗口存储
     * @param max 窗口大小
     * @return 已绑定记忆的对话接口
     */
    public LuoyeAssistant create(UUID id, PersistentChatMemoryStore store, int max) {
        return AiServices.builder(LuoyeAssistant.class)
                .streamingChatLanguageModel(model)
                .chatMemory(MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(max)
                        .chatMemoryStore(store)
                        .build())
                .build();
    }
}
```

## back-end-LangChan4j/luoye-service/src/main/java/com/luoye/service/chat/ChatService.java

```java
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
```

## back-end-LangChan4j/luoye-api/src/main/java/com/luoye/api/config/AuthConfig.java

```java
package com.luoye.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.luoye.dao.repository.ChatRepository;

/**
 * 单用户账号初始化配置，依赖 ChatRepository 和 BCrypt 密码编码器。
 *
 * <p>仅在配置了初始密码且账号不存在时创建账号，不覆盖已有密码。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Configuration
public class AuthConfig {

    /** @return 用于首启密码保存和登录密码匹配的 BCrypt 编码器 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 构建启动时执行的账号初始化任务。
     *
     * @param repo 用户查询和写入仓储
     * @param encoder 密码哈希编码器
     * @param username 首启登录名
     * @param password 环境变量提供的首启密码；为空时跳过初始化
     * @return 启动后执行的初始化回调
     */
    @Bean
    CommandLineRunner bootstrapAccount(
            ChatRepository repo,
            PasswordEncoder encoder,
            @Value("\u0024{luoye.auth.username:admin}") String username,
            @Value("\u0024{luoye.auth.password:}") String password) {
        return args -> {
            if (password != null && !password.isBlank() && repo.account(username).isEmpty()) {
                repo.createAccount(username, encoder.encode(password));
            }
        };
    }
}
```

## back-end-LangChan4j/luoye-api/src/main/java/com/luoye/api/config/JwtAuthFilter.java

```java
package com.luoye.api.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 Bearer 令牌恢复当前请求的用户身份，依赖 JwtService 验证签名与有效期。
 *
 * <p>无令牌或无效令牌时不设置身份，是否允许请求继续由安全规则判断。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    /** @param jwt 令牌签发与校验服务 */
    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    /**
     * 校验令牌并写入 Spring Security 的请求上下文。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param chain 后续过滤器链
     * @throws ServletException 后续过滤器处理异常
     * @throws IOException 请求或响应读写失败
     */
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = request.getHeader("Authorization");
        if (value != null && value.startsWith("Bearer ")) {
            try {
                var id = jwt.subject(value.substring(7));
                var auth = new UsernamePasswordAuthenticationToken(
                        id.toString(), null, AuthorityUtils.NO_AUTHORITIES);
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(auth);
            } catch (RuntimeException ignored) {
                // 验证失败不写入身份，继续由安全过滤链执行访问控制。
            }
        }
        chain.doFilter(request, response);
    }
}
```

## back-end-LangChan4j/luoye-api/src/main/java/com/luoye/api/config/JwtService.java

```java
package com.luoye.api.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKey;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与验证服务，依赖 JJWT 和环境变量提供的对称密钥。
 *
 * <p>令牌 subject 保存用户 UUID。密钥缺失时启动失败，不在本类中自动生成密钥。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationHours;

    /**
     * 加载 JWT 签名配置。
     *
     * @param secret 至少 32 字符的签名密钥
     * @param expirationHours 有效期小时数
     * @throws IllegalStateException 密钥为空或字符数不足
     */
    public JwtService(
            @Value("\u0024{luoye.security.jwt.secret:}") String secret,
            @Value("\u0024{luoye.security.jwt.exp-hours:1}") long expirationHours) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("LUOYE_JWT_SECRET must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationHours = expirationHours;
    }

    /**
     * 为已通过密码校验的用户签发令牌。
     *
     * @param userId 用户 UUID
     * @param username 登录名，附在自定义 claim 中
     * @return 带签名和有效期的 JWT
     */
    public String issue(UUID userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationHours * 3600)))
                .signWith(key)
                .compact();
    }

    /**
     * 验证签名及有效期后读取用户 UUID。
     *
     * @param token 不含 Bearer 前缀的 JWT
     * @return 令牌中的用户 UUID
     * @throws JwtException 令牌过期、签名不匹配或结构无效
     * @throws IllegalArgumentException subject 不是 UUID 或输入非法
     */
    public UUID subject(String token) {
        return UUID.fromString(Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject());
    }
}
```

## back-end-LangChan4j/luoye-api/src/main/java/com/luoye/api/config/ModelConfig.java

```java
package com.luoye.api.config;

import java.time.Duration;
import java.util.concurrent.*;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/**
 * 流式模型与任务执行器配置，依赖 LangChain4j 的供应商适配器。
 *
 * <p>按启动配置选择 OpenAI 兼容接口或 Ollama；此处不实现运行时模型切换。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Configuration
public class ModelConfig {

    /**
     * 根据自定义 langchain4j.chat 配置创建流式模型。
     *
     * @param env Spring 环境配置
     * @return 后续 Assistant 共享的模型客户端
     * @throws IllegalStateException 模型名或必要 API Key 缺失，或供应商不支持
     */
    @Bean
    StreamingChatLanguageModel chatModel(Environment env) {
        String provider = env.getProperty("langchain4j.chat.provider", "openai-compatible");
        String model = env.getProperty("langchain4j.chat.model", "");
        if (model.isBlank()) {
            throw new IllegalStateException("Please configure LLM_CHAT_MODEL");
        }
        Duration timeout = Duration.ofSeconds(
                env.getProperty("langchain4j.chat.timeout-seconds", Long.class, 60L));
        double temperature = env.getProperty("langchain4j.chat.temperature", Double.class, 0.7);
        String base = env.getProperty("langchain4j.chat.base-url", "");
        if (provider.equals("ollama")) {
            // Ollama 默认使用本地地址，不要求云端 API Key。
            return OllamaStreamingChatModel.builder()
                    .baseUrl(base.isBlank() ? env.getProperty("langchain4j.ollama.base-url") : base)
                    .modelName(model)
                    .temperature(temperature)
                    .timeout(timeout)
                    .build();
        }
        if (!provider.equals("openai-compatible")) {
            throw new IllegalStateException("Unsupported LLM_CHAT_PROVIDER");
        }
        String key = env.getProperty("langchain4j.chat.api-key", "");
        if (key.isBlank()) {
            throw new IllegalStateException("Please configure LLM_CHAT_API_KEY");
        }
        return OpenAiStreamingChatModel.builder()
                .baseUrl(base.isBlank() ? "https://api.openai.com/v1" : base)
                .apiKey(key)
                .modelName(model)
                .temperature(temperature)
                .timeout(timeout)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 限制待启动生成任务的队列，队列满时让 ChatService 返回繁忙事件。
     *
     * @return 由 Spring 在关闭时停止的执行器；不等同于供应商在途请求数限制
     */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService chatExecutor() {
        return new ThreadPoolExecutor(
                2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
    }

    /** @return 为生成任务安排截止时间的单线程调度器 */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService chatScheduler() {
        return Executors.newSingleThreadScheduledExecutor();
    }
}
```

## back-end-LangChan4j/luoye-api/src/main/java/com/luoye/api/config/SecurityConfig.java

```java
package com.luoye.api.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;

/**
 * HTTP 访问控制配置，依赖 JWT 过滤器恢复身份。
 *
 * <p>登录和预留的健康检查路径允许匿名访问，其他路径要求认证；不创建 HTTP Session。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** @param jwt 令牌校验服务 @return Bearer 认证过滤器 */
    @Bean
    JwtAuthFilter jwtAuthFilter(JwtService jwt) {
        return new JwtAuthFilter(jwt);
    }

    /**
     * 构建无状态认证过滤链；路径放行规则本身不创建健康检查端点。
     *
     * @param http Spring Security 构建器
     * @param jwtFilter 从 Authorization 请求头解析用户身份的过滤器
     * @return 安全过滤链
     * @throws Exception 过滤链配置失败
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtFilter)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/actuator/health", "/error")
                        .permitAll().anyRequest().authenticated())
                .addFilterBefore(jwtFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

## back-end-LangChan4j/luoye-api/src/main/java/com/luoye/api/controller/AuthController.java

```java
package com.luoye.api.controller;

import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import com.luoye.api.config.JwtService;
import com.luoye.dao.repository.ChatRepository;

/**
 * 单用户登录端点，依赖用户仓储、密码编码器和 JWT 服务。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** 登录请求；password 为本次待校验密码，不在此处存储明文。 */
    public record LoginRequest(String username, String password) {}

    private final ChatRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    /**
     * @param repo 用户仓储
     * @param encoder 与账号初始化一致的密码哈希编码器
     * @param jwt 令牌签发服务
     */
    public AuthController(ChatRepository repo, PasswordEncoder encoder, JwtService jwt) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /**
     * 校验密码后返回令牌。
     *
     * @param request 登录名与密码
     * @return token 及 expiresIn 字段
     * @throws org.springframework.web.server.ResponseStatusException 账号或密码不匹配
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        var account = repo.account(request.username()).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (account.passwordHash() == null
                || !encoder.matches(request.password(), account.passwordHash())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        // 已知限制：响应固定报告 3600 秒，令牌实际过期时间由 JwtService 配置决定。
        return Map.of("token", jwt.issue(account.id(), account.username()), "expiresIn", 3600);
    }
}
```

## back-end-LangChan4j/luoye-api/src/main/java/com/luoye/api/controller/ApiExceptionHandler.java

```java
package com.luoye.api.controller;

import java.util.Map;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import com.luoye.common.ApiException;

/**
 * 控制器异常响应转换，依赖 Spring MVC 的全局异常处理机制。
 *
 * <p>只向客户端暴露业务错误或通用提示，不返回底层异常详情。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** @param e 带业务错误码的异常 @return 指定 HTTP 状态及错误 JSON */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    /**
     * 处理未单独映射的异常。
     *
     * <p>已知限制：当前也会捕获 ResponseStatusException，将其原状态覆盖为 500。
     *
     * @param e 未被业务异常分支处理的异常
     * @return 通用 500 响应
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        return ResponseEntity.status(500)
                .body(Map.of("code", "internal_error", "message", "服务暂不可用"));
    }
}
```

## back-end-LangChan4j/luoye-api/src/main/java/com/luoye/api/controller/SessionController.java

```java
package com.luoye.api.controller;

import java.util.*;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.luoye.api.config.JwtService;
import com.luoye.common.ApiException;
import com.luoye.dao.repository.ChatRepository;
import com.luoye.service.chat.ChatService;

/**
 * M1 会话与 SSE 接口，依赖 ChatRepository 和 ChatService。
 *
 * <p>从已认证身份读取用户 UUID，创建或查询会话，并把生成事件写入 SSE 响应。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

    /** 新会话请求；title 为空时由前端显示“未命名会话”。 */
    public record CreateSessionRequest(String title) {}

    /** 消息请求；stream 和 regenerateOf 目前只保留字段，尚未参与控制流。 */
    public record SendMessageRequest(String content, Boolean stream, UUID regenerateOf) {}

    private final ChatRepository repo;
    private final ChatService chat;

    /** @param repo 会话仓储 @param chat 流式生成服务 */
    public SessionController(ChatRepository repo, ChatService chat) {
        this.repo = repo;
        this.chat = chat;
    }

    /** 从认证上下文提取用户 UUID；身份异常转换为业务认证错误。 */
    private UUID owner(Authentication auth) {
        try {
            return UUID.fromString(auth.getName());
        } catch (Exception e) {
            throw new ApiException(401, "unauthorized", "无效的认证信息");
        }
    }

    /** @param auth 当前认证身份 @return 当前用户的会话列表 */
    @GetMapping("/sessions")
    public List<ChatRepository.Session> sessions(Authentication auth) {
        return repo.sessions(owner(auth));
    }

    /**
     * 创建会话并立即持久化。
     *
     * @param auth 当前认证身份
     * @param request 可省略的标题请求
     * @return 新建会话
     */
    @PostMapping("/sessions")
    public ChatRepository.Session create(
            Authentication auth, @RequestBody(required = false) CreateSessionRequest request) {
        return repo.createSession(owner(auth), request == null ? null : request.title());
    }

    /**
     * @param auth 当前认证身份
     * @param id 会话 UUID
     * @return 按 seq 正序排列的历史消息；会话归属在仓储中校验
     */
    @GetMapping("/sessions/{id}/messages")
    public List<ChatRepository.Message> messages(Authentication auth, @PathVariable UUID id) {
        return repo.history(owner(auth), id);
    }

    /**
     * 接受一条消息并返回 SSE 长连接。
     *
     * @param auth 当前认证身份
     * @param sessionId 目标会话
     * @param request 当前消息内容
     * @return 承载 start、delta、done 和 error 事件的响应
     * @throws ApiException 会话不属于当前用户、内容非法或会话正在生成
     */
    @PostMapping(value = "/chat/sessions/{sessionId}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(
            Authentication auth,
            @PathVariable UUID sessionId,
            @RequestBody SendMessageRequest request) {
        UUID user = owner(auth);
        ChatService.Run run = chat.reserve(
                user, sessionId, request == null ? null : request.content());
        // 此处连接超时固定为 125 秒，独立于 ChatService 中可配置的生成超时。
        SseEmitter emitter = new SseEmitter(125000L);
        emitter.onTimeout(() -> chat.disconnect(run));
        emitter.onCompletion(() -> chat.disconnect(run));
        emitter.onError(ex -> chat.disconnect(run));
        chat.start(run, (event, payload) -> {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (Exception e) {
                // 将传输失败交回 ChatService，使其释放对应会话的生成槽位。
                throw new IllegalStateException(e);
            }
        }, emitter::complete);
        return emitter;
    }

    /**
     * @param auth 当前认证身份
     * @param sessionId 会话 UUID
     * @param runId 由 start 事件返回的本轮生成 UUID
     * @return 是否成功停止仍在运行的服务端生成流程
     */
    @DeleteMapping("/chat/sessions/{sessionId}/streams/{runId}")
    public Map<String, Object> abort(
            Authentication auth, @PathVariable UUID sessionId, @PathVariable UUID runId) {
        return Map.of("runId", runId, "aborted", chat.abort(owner(auth), sessionId, runId));
    }
}
```

## front-end/src/api/index.js

```javascript
/**
 * M1 HTTP 与 SSE 客户端，依赖浏览器 fetch、ReadableStream 和 localStorage。
 * 为会话请求附加 JWT，并将服务器事件分发给对话页。
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
const baseURL = import.meta.env.VITE_API_BASE_URL || '/api/v1'

/**
 * 发起 API 请求；失败时抛出带 HTTP status 的 Error。
 * @param {string} path 相对于 API 根地址的路径
 * @param {RequestInit} options fetch 请求选项
 * @returns {Promise<Response>} 成功响应，正文由调用方读取
 */
async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = localStorage.getItem('luoye_token')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !(options.body instanceof FormData))
    headers.set('Content-Type', 'application/json')
  const response = await fetch(`${baseURL}${path}`, { ...options, headers })
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const body = await response.json()
      message = body.message || message
    } catch (_) {
      /* 非 JSON 或空响应时保留通用错误信息。 */
    }
    const error = new Error(message)
    error.status = response.status
    throw error
  }
  return response
}

/**
 * 校验登录凭据；此函数不负责保存令牌。
 * @param {string} username 登录名
 * @param {string} password 登录密码
 * @returns {Promise<object>} 包含 token 和 expiresIn 的响应
 */
export async function login(username, password) {
  const response = await request('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  return response.json()
}
/** @returns {Promise<Array>} 当前用户的会话列表 */
export async function listSessions() {
  return (await request('/sessions')).json()
}
/**
 * 创建并持久化空会话。
 * @param {string|null} title 会话标题；空值显示为未命名会话
 * @returns {Promise<object>} 新建会话
 */
export async function createSession(title = null) {
  return (
    await request('/sessions', {
      method: 'POST',
      body: JSON.stringify({ title }),
    })
  ).json()
}
/**
 * @param {string} sessionId 会话 UUID
 * @returns {Promise<Array>} 按会话序号排列的完整历史
 */
export async function listMessages(sessionId) {
  return (await request(`/sessions/${sessionId}/messages`)).json()
}

/**
 * 通过 POST 发送消息并读取 SSE；fetch 允许携带 Bearer 头和 JSON 请求体。
 * @param {string} sessionId 会话 UUID
 * @param {string} content 当前用户消息
 * @param {Object<string, Function>} handlers 按事件名索引的回调
 * @returns {Promise<void>} 响应流结束时完成，传输错误时拒绝
 */
export async function streamMessage(sessionId, content, handlers = {}) {
  const response = await request(`/chat/sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content, stream: true }),
  })
  if (!response.body) throw new Error('浏览器不支持流式响应')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  // 网络块可能拆开一个 SSE 事件，buffer 保留尚未收到空行分隔符的尾部。
  const consume = (raw) => {
    buffer += raw
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() || ''
    for (const block of blocks) {
      let event = 'message'
      const data = []
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).trim())
      }
      // 没有 data 的心跳或注释帧不应触发业务回调。
      if (!data.length) continue
      let payload
      try {
        payload = JSON.parse(data.join('\n'))
      } catch (_) {
        payload = { content: data.join('\n') }
      }
      const handler = handlers[event]
      if (handler) handler(payload)
    }
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      // 使用流式解码，避免一个中文字符跨网络块时被拆成乱码。
      if (value) consume(decoder.decode(value, { stream: !done }))
      if (done) break
    }
    if (buffer.trim()) consume('\n\n')
  } finally {
    reader.releaseLock()
  }
}

/**
 * 请求后端关闭指定生成任务，不直接取消此处的 fetch 读取器。
 * @param {string} sessionId 会话 UUID
 * @param {string} runId start 事件返回的生成 UUID
 * @returns {Promise<object>} 包含 aborted 标记的响应
 */
export async function abortStream(sessionId, runId) {
  return (
    await request(`/chat/sessions/${sessionId}/streams/${runId}`, {
      method: 'DELETE',
    })
  ).json()
}
```

## front-end/src/stores/auth.js

```javascript
/**
 * 登录状态模块，依赖 Pinia、Vue 和登录 API。
 * 本地持有令牌只用于恢复界面状态；有效性仍由服务端验证。
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { login as loginRequest } from '@/api/index.js'

/** 创建或取得共享登录状态。 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('luoye_token'))
  const signedIn = computed(() => Boolean(token.value))
  /**
   * 登录成功后更新内存状态和浏览器存储；异常交由页面展示。
   * @param {string} username 登录名
   * @param {string} password 密码
   * @returns {Promise<void>}
   */
  async function login(username, password) {
    const data = await loginRequest(username, password)
    token.value = data.token
    localStorage.setItem('luoye_token', data.token)
  }
  /** 清除浏览器令牌；不会吊销服务器已签发的 JWT。 */
  function logout() {
    token.value = null
    localStorage.removeItem('luoye_token')
  }
  return { token, signedIn, login, logout }
})
```

## front-end/src/stores/session.js

```javascript
/**
 * 会话与历史消息状态，依赖 Pinia、Vue 和会话 API。
 * 对话页共享该状态，新增消息的生成状态由页面管理。
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
import { createSession, listMessages, listSessions } from '@/api/index.js'

/** 创建或取得会话列表、当前会话和消息状态。 */
export const useSessionStore = defineStore('session', () => {
  const sessions = ref([])
  const currentSessionId = ref(null)
  const messages = ref([])
  const loading = ref(false)
  /** @returns {Promise<Array>} 刷新并返回服务器上的会话列表 */
  async function refresh() {
    sessions.value = await listSessions()
    return sessions.value
  }
  /**
   * 切换当前会话并以服务器历史替换消息列表。
   * @param {string} id 目标会话 UUID
   * @returns {Promise<void>}
   */
  async function open(id) {
    currentSessionId.value = id
    messages.value = await listMessages(id)
  }
  /** @returns {Promise<object>} 创建、置顶并打开的新会话 */
  async function newSession() {
    const session = await createSession()
    sessions.value.unshift(session)
    await open(session.id)
    return session
  }
  /** 加载列表及首个会话；无会话时保持空状态，失败时仍释放 loading。 */
  async function loadFirst() {
    loading.value = true
    try {
      await refresh()
      if (sessions.value.length) await open(sessions.value[0].id)
    } finally {
      loading.value = false
    }
  }
  return {
    sessions,
    currentSessionId,
    messages,
    loading,
    refresh,
    open,
    newSession,
    loadFirst,
  }
})
```

## front-end/src/views/ChatView.vue

```vue
<script setup>
/**
 * M1 对话页，依赖登录/会话 Store 与 fetch SSE 客户端。
 * 展示登录、会话列表、历史消息和流式回复，并支持发送停止请求。
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
import { nextTick, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth.js'
import { useSessionStore } from '@/stores/session.js'
import { abortStream, streamMessage } from '@/api/index.js'

const auth = useAuthStore()
const sessions = useSessionStore()
const input = ref('')
const sending = ref(false)
const error = ref('')
const loginForm = ref({ username: 'admin', password: '' })
const messagesEl = ref(null)

/** 等待 Vue 完成 DOM 更新后滚动到底部。 */
function scrollBottom() {
  nextTick(() => {
    if (messagesEl.value)
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
  })
}
/** 登录成功后恢复会话列表，将登录或加载错误展示在页面上。 */
async function signIn() {
  error.value = ''
  try {
    await auth.login(loginForm.value.username, loginForm.value.password)
    await sessions.loadFirst()
  } catch (e) {
    error.value = e.message
  }
}
/**
 * 生成期间不切换会话，避免回复出现在另一会话的消息列表里。
 * @param {string} id 要打开的会话 UUID
 */
async function selectSession(id) {
  if (sending.value) return
  error.value = ''
  try {
    await sessions.open(id)
    scrollBottom()
  } catch (e) {
    error.value = e.message
  }
}
/** 创建并打开空会话；生成过程中忽略操作。 */
async function createNew() {
  if (sending.value) return
  error.value = ''
  try {
    await sessions.newSession()
  } catch (e) {
    error.value = e.message
  }
}
/** 发送当前输入，创建临时消息气泡，并根据 SSE 回调更新显示。 */
async function send() {
  const content = input.value.trim()
  if (!content || sending.value) return
  if (!sessions.currentSessionId) await createNew()
  if (!sessions.currentSessionId) return
  error.value = ''
  input.value = ''
  sending.value = true
  // 临时 ID 只用于本页渲染；重新打开会话后由数据库历史替换。
  const userMessage = { id: `local-user-${Date.now()}`, role: 'USER', content }
  const assistantMessage = {
    id: `local-assistant-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
  }
  sessions.messages.push(userMessage, assistantMessage)
  scrollBottom()
  let runId = null
  try {
    await streamMessage(sessions.currentSessionId, content, {
      // start 提供停止生成所需的 runId；delta 每次只包含新增文本。
      start: (payload) => {
        runId = payload.runId
        runIdForStop.value = payload.runId
      },
      delta: (payload) => {
        assistantMessage.content += payload.content || ''
        scrollBottom()
      },
      done: () => {
        sending.value = false
        runIdForStop.value = null
        scrollBottom()
      },
      error: (payload) => {
        error.value = payload.message || '生成失败'
        assistantMessage.content = ''
        sending.value = false
      },
    })
  } catch (e) {
    error.value = e.message
    assistantMessage.content = ''
    sending.value = false
  } finally {
    if (sending.value) sending.value = false
    runIdForStop.value = null
    await sessions.refresh()
  }
}
/** 使用 start 事件返回的 runId 请求停止当前生成。 */
async function stop() {
  if (!sending.value || !runIdForStop.value) return
  try {
    await abortStream(sessions.currentSessionId, runIdForStop.value)
  } catch (_) {
    /* 当前忽略停止请求错误，原响应流仍按其生命周期继续。 */
  }
}
const runIdForStop = ref(null)
// 本地令牌存在时尝试恢复会话；服务端仍会验证令牌是否有效。
onMounted(async () => {
  if (auth.signedIn) {
    try {
      await sessions.loadFirst()
    } catch (e) {
      error.value = e.message
    }
  }
})
</script>

<template>
  <!-- 登录状态只决定界面展示，API 的实际访问控制在后端。 -->
  <section
    v-if="!auth.signedIn"
    class="mx-auto max-w-md rounded-xl border border-stone-200 bg-white p-6 shadow-sm"
  >
    <h2 class="mb-2">登录落叶</h2>
    <p class="mb-5 text-sm text-stone-500">使用后端配置的单用户账号继续。</p>
    <form class="space-y-4" @submit.prevent="signIn">
      <input
        v-model="loginForm.username"
        class="w-full rounded border p-2"
        placeholder="用户名"
        autocomplete="username"
      />
      <input
        v-model="loginForm.password"
        class="w-full rounded border p-2"
        type="password"
        placeholder="密码"
        autocomplete="current-password"
      />
      <button
        class="w-full rounded bg-emerald-700 px-4 py-2 text-white"
        type="submit"
      >
        登录
      </button>
    </form>
    <p v-if="error" class="mt-4 text-sm text-red-600">{{ error }}</p>
  </section>
  <section v-else class="flex h-[calc(100vh-3rem)] min-h-[520px] gap-4">
    <aside
      class="flex w-64 shrink-0 flex-col rounded-xl border border-stone-200 bg-white p-3"
    >
      <div class="mb-3 flex items-center justify-between">
        <h2 class="text-lg">会话</h2>
        <button
          class="rounded bg-emerald-700 px-3 py-1 text-sm text-white"
          @click="createNew"
        >
          新建
        </button>
      </div>
      <div class="space-y-1 overflow-y-auto">
        <button
          v-for="session in sessions.sessions"
          :key="session.id"
          class="block w-full rounded px-3 py-2 text-left text-sm hover:bg-stone-100"
          :class="
            session.id === sessions.currentSessionId
              ? 'bg-emerald-50 font-medium'
              : ''
          "
          @click="selectSession(session.id)"
        >
          {{ session.title || '未命名会话' }}
        </button>
      </div>
      <button
        class="mt-auto pt-4 text-left text-sm text-stone-500"
        @click="auth.logout"
      >
        退出登录
      </button>
    </aside>
    <div
      class="flex min-w-0 flex-1 flex-col rounded-xl border border-stone-200 bg-white"
    >
      <div ref="messagesEl" class="flex-1 space-y-4 overflow-y-auto p-5">
        <div
          v-if="!sessions.messages.length"
          class="py-16 text-center text-stone-400"
        >
          新建会话，开始聊天
        </div>
        <article
          v-for="message in sessions.messages"
          :key="message.id"
          class="flex"
          :class="message.role === 'USER' ? 'justify-end' : 'justify-start'"
        >
          <div
            class="max-w-[80%] whitespace-pre-wrap rounded-2xl px-4 py-3"
            :class="
              message.role === 'USER'
                ? 'bg-emerald-700 text-white'
                : 'bg-stone-100 text-stone-800'
            "
          >
            {{
              message.content ||
              (sending && message.role === 'ASSISTANT' ? '▌' : '')
            }}
          </div>
        </article>
      </div>
      <p v-if="error" class="px-5 text-sm text-red-600">{{ error }}</p>
      <form
        class="flex gap-3 border-t border-stone-100 p-4"
        @submit.prevent="send"
      >
        <textarea
          v-model="input"
          class="min-h-12 flex-1 resize-none rounded border p-3"
          placeholder="输入消息，Enter 发送"
          @keydown.enter.exact.prevent="send"
        /><button
          v-if="sending"
          type="button"
          class="rounded border border-stone-300 px-5 py-2"
          @click="stop"
        >
          停止</button
        ><button
          v-else
          class="rounded bg-emerald-700 px-5 py-2 text-white disabled:opacity-50"
          :disabled="!input.trim()"
        >
          发送
        </button>
      </form>
    </div>
  </section>
</template>
```