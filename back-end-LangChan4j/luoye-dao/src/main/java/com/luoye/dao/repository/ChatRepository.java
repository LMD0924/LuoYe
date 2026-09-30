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