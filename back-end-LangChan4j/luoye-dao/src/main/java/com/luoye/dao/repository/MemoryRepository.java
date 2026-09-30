package com.luoye.dao.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.luoye.common.ApiException;

/**
 * 长期记忆仓储，依赖 PostgreSQL/pgvector 和 Spring JDBC。
 * 所有用户入口均使用 user_id 隔离；网络调用不得放入 mutate 事务。
 * @author Codex
 * @since 2026-09-30
 */
@Repository
public class MemoryRepository {
    /** 向客户端返回的记忆投影，不暴露向量本身。 */
    public record Item(UUID id, String content, String memoryType, String source,
                       String confidence, String status, double importanceWeight,
                       int accessCount, boolean neverDecay, int correctionCount,
                       Instant createdAt, Instant updatedAt, Instant lastAccessAt,
                       Instant nextReviewAt, boolean embeddingReady, boolean cold,
                       double decayScore, UUID originSessionId, UUID originMessageId) {}

    /** 列表分页响应。 */
    public record Page(List<Item> items, long total, int offset, int limit) {}

    /** 隐式抽取仅读取游标之后的用户原话，不抽取模型自己的回答。 */
    public record Input(UUID id, int seq, String content) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private static Instant instant(ResultSet r, String name) throws SQLException {
        var value = r.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static final RowMapper<Item> ROW = (r, n) -> new Item(
            r.getObject("id", UUID.class), r.getString("content"), r.getString("memory_type"),
            r.getString("source"), r.getString("confidence"), r.getString("status"),
            r.getDouble("importance_weight"), r.getInt("access_count"), r.getBoolean("never_decay"),
            r.getInt("correction_count"), instant(r, "created_at"), instant(r, "updated_at"),
            instant(r, "last_access_at"), instant(r, "next_review_at"),
            r.getBoolean("embedding_ready"), r.getBoolean("cold"), r.getDouble("decay_score"),
            r.getObject("origin_session_id", UUID.class), r.getObject("origin_message_id", UUID.class));
    private static final String SELECT = "SELECT *, embedding IS NOT NULL AS embedding_ready FROM long_term_memory ";

    /** @param jdbc JDBC 模板 @param tx 事务模板 */
    public MemoryRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** @return 当前用户记忆修订号，用于拒绝网络调用期间已经过期的抽取结果。 */
    public long revision(UUID owner) {
        return jdbc.queryForObject("SELECT memory_revision FROM users WHERE id=?", Long.class, owner);
    }

    /**
     * 锁定用户记忆域并提交一个原子变更；同用户的管理操作和抽取提交串行执行。
     * @param expected 抽取前读取的修订号；管理操作传 null
     * @throws ApiException 记忆已被其他请求更新时返回 409
     */
    public <T> T mutate(UUID owner, Long expected, Supplier<T> action) {
        return tx.execute(status -> {
            long current = jdbc.queryForObject(
                    "SELECT memory_revision FROM users WHERE id=? FOR UPDATE", Long.class, owner);
            if (expected != null && expected != current) {
                throw new ApiException(409, "memory_changed", "记忆已更新，请重试本次操作");
            }
            T result = action.get();
            jdbc.update("UPDATE users SET memory_revision=memory_revision+1 WHERE id=?", owner);
            return result;
        });
    }

    /** 根据类型、状态、文本分页过滤；SQL 参数化处理搜索内容。 */
    public Page list(UUID owner, String type, String state, String q, int offset, int limit) {
        String where = "WHERE user_id=? AND deleted_at IS NULL AND status<>'deleted'";
        List<Object> args = new ArrayList<>(List.of(owner));
        if (type != null && !type.isBlank()) { where += " AND memory_type=?"; args.add(type); }
        if (state != null && !state.isBlank()) { where += " AND status=?"; args.add(state); }
        if (q != null && !q.isBlank()) { where += " AND strpos(lower(content),lower(?))>0"; args.add(q); }
        long total = jdbc.queryForObject("SELECT count(*) FROM long_term_memory " + where,
                Long.class, args.toArray());
        args.add(limit);
        args.add(offset);
        return new Page(jdbc.query(SELECT + where + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                ROW, args.toArray()), total, offset, limit);
    }

    /** 读取单条未删除记忆；其他用户的 UUID 一律视为不存在。 */
    public Item require(UUID owner, UUID id) {
        return jdbc.query(SELECT + "WHERE user_id=? AND id=? AND deleted_at IS NULL AND status<>'deleted'",
                ROW, owner, id).stream().findFirst().orElseThrow(
                () -> new ApiException(404, "memory_not_found", "记忆不存在"));
    }

    /** @return 供抽取判断纠偏目标的有限快照，包含待确认项和纠正次数。 */
    public List<Item> candidates(UUID owner) {
        return jdbc.query(SELECT + "WHERE user_id=? AND deleted_at IS NULL AND status IN ('active','pending') "
                + "ORDER BY coalesce(updated_at,created_at) DESC LIMIT 100", ROW, owner);
    }

    /** @return 与规范化文本摘要相同的活动或待确认记忆。 */
    public Optional<Item> exact(UUID owner, String hash) {
        return jdbc.query(SELECT + "WHERE user_id=? AND content_hash=? AND deleted_at IS NULL "
                + "AND status IN ('active','pending') LIMIT 1", ROW, owner, hash).stream().findFirst();
    }

    /** @return 用户是否曾删除或纠正过该事实；隐式抽取不得恢复它。 */
    public boolean suppressed(UUID owner, String hash) {
        return jdbc.queryForObject("SELECT count(*) FROM memory_suppression WHERE user_id=? AND content_hash=?",
                Long.class, owner, hash) > 0;
    }

    /** 只存摘要，不另外保存被遗忘的敏感原文。 */
    public void suppress(UUID owner, String hash) {
        jdbc.update("INSERT INTO memory_suppression(user_id,content_hash) VALUES (?,?) ON CONFLICT DO NOTHING",
                owner, hash);
    }

    /** 插入抽取结果；向量失败时允许为空，由补建任务重试。 */
    public UUID insert(UUID owner, String text, String type, String source, String confidence,
                       boolean neverDecay, UUID sessionId, UUID messageId, String hash,
                       String vector, String model) {
        UUID id = UUID.randomUUID();
        String state = source.equals("inferred") && confidence.equals("low") ? "pending" : "active";
        jdbc.update("""
                INSERT INTO long_term_memory(id,user_id,content,memory_type,source,confidence,status,
                    never_decay,origin_session_id,origin_message_id,content_hash,embedding,embedding_model,
                    updated_at,next_review_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,CAST(? AS vector),?,now(),now()+interval '7 days')
                """, id, owner, text, type, source, confidence, state, neverDecay, sessionId,
                messageId, hash, vector, vector == null ? null : model);
        return id;
    }

    /** 用户纠偏同时替换文本与向量；失败的嵌入清空旧向量，不能继续召回旧事实。 */
    public void correct(UUID owner, UUID id, String text, String hash, String vector,
                        String model, UUID messageId, boolean neverDecay) {
        jdbc.update("""
                UPDATE long_term_memory SET content=?,content_hash=?,embedding=CAST(? AS vector),
                    embedding_model=?,source='explicit',confidence='high',status='active',
                    correction_count=correction_count+1,never_decay=?,updated_at=now(),
                    cold=false,decay_score=1,next_review_at=now()+interval '7 days'
                WHERE user_id=? AND id=? AND deleted_at IS NULL
                """, text, hash, vector, vector == null ? null : model, neverDecay, owner, id);
        jdbc.update("""
                INSERT INTO memory_correction(id,user_id,memory_id,corrected_value,source_message_id)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), owner, id, text, messageId);
    }

    /** 软删除并立即清除向量；调用者需在同一事务登记抑制摘要。 */
    public void delete(UUID owner, UUID id) {
        jdbc.update("UPDATE long_term_memory SET status='deleted',deleted_at=now(),updated_at=now(),"
                + "embedding=NULL,embedding_model=NULL WHERE user_id=? AND id=?", owner, id);
    }

    /** 确认仅接受 pending 项，确认后按用户认可事实处理。 */
    public void confirm(UUID owner, UUID id) {
        jdbc.update("UPDATE long_term_memory SET status='active',confidence='high',source='explicit',"
                + "updated_at=now() WHERE user_id=? AND id=? AND status='pending'", owner, id);
    }

    /** @return 活动记忆的余弦近邻；模型标识隔离避免混用不同嵌入空间。 */
    public List<Item> nearest(UUID owner, String vector, String model, int limit, double similarity) {
        return jdbc.query(SELECT + "WHERE user_id=? AND status='active' AND deleted_at IS NULL "
                + "AND embedding_model=? AND embedding IS NOT NULL AND (embedding <=> CAST(? AS vector))<=? "
                + "ORDER BY embedding <=> CAST(? AS vector) LIMIT ?", ROW,
                owner, model, vector, 1 - similarity, vector, limit);
    }

    /** 中文关键词和 FTS/trigram 降级，不向已删除/待确认记忆放宽过滤。 */
    public List<Item> search(UUID owner, String query, String keyword, int limit) {
        return jdbc.query(SELECT + """
                WHERE user_id=? AND status='active' AND deleted_at IS NULL AND
                  (strpos(lower(content),lower(?))>0
                  OR to_tsvector('simple',content) @@ plainto_tsquery('simple',?)
                  OR similarity(content,?)>0.08)
                ORDER BY similarity(content,?) DESC,updated_at DESC LIMIT ?
                """, ROW, owner, keyword.isBlank() ? query : keyword, query, query, query, limit);
    }

    /** 常驻热区每次从数据库读取，编辑/删除后不会残留旧缓存。 */
    public List<Item> hot(UUID owner, int limit) {
        return jdbc.query(SELECT + "WHERE user_id=? AND status='active' AND deleted_at IS NULL AND cold=false "
                + "AND (never_decay=true OR (importance_weight>=0.7 AND access_count>=3)) "
                + "ORDER BY never_decay DESC,access_count DESC LIMIT ?", ROW, owner, limit);
    }

    /** 记录实际送入模型的访问量。 */
    public void touch(UUID owner, UUID id) {
        jdbc.update("UPDATE long_term_memory SET access_count=access_count+1,last_access_at=now() "
                + "WHERE user_id=? AND id=? AND status='active' AND deleted_at IS NULL", owner, id);
    }

    /** @return 当前会话尚未抽取的用户消息，限制批次大小。 */
    public List<Input> unprocessed(UUID sessionId) {
        return jdbc.query("""
                SELECT id,seq,content FROM messages WHERE session_id=? AND role='USER'
                AND seq>coalesce((SELECT last_seq FROM memory_extraction_cursor WHERE session_id=?),0)
                ORDER BY seq LIMIT 30
                """, (r,n) -> new Input(r.getObject("id", UUID.class), r.getInt("seq"), r.getString("content")),
                sessionId, sessionId);
    }

    /** 推进成功抽取的游标，重启后不会重复处理整段历史。 */
    public void checkpoint(UUID sessionId, int seq) {
        jdbc.update("INSERT INTO memory_extraction_cursor(session_id,last_seq) VALUES (?,?) "
                + "ON CONFLICT (session_id) DO UPDATE SET last_seq=greatest(memory_extraction_cursor.last_seq,excluded.last_seq)",
                sessionId, seq);
    }

    /** 清空时跳过所有旧对话，防止下一轮隐式抽取重新导入清空前的内容。 */
    public void skipHistory(UUID owner) {
        jdbc.update("""
                INSERT INTO memory_extraction_cursor(session_id,last_seq)
                SELECT s.id,coalesce(max(m.seq),0) FROM sessions s LEFT JOIN messages m ON m.session_id=s.id
                WHERE s.user_id=? GROUP BY s.id
                ON CONFLICT (session_id) DO UPDATE SET last_seq=excluded.last_seq
                """, owner);
    }

    /** 批量任务只返回主键，处理前还需在用户锁内重新检查状态。 */
    public List<Map<String,Object>> maintenanceTargets(boolean vectors, String model) {
        return jdbc.queryForList(vectors
                ? "SELECT id,user_id FROM long_term_memory WHERE deleted_at IS NULL AND status IN ('active','pending') "
                  + "AND (embedding IS NULL OR embedding_model IS DISTINCT FROM ?) ORDER BY created_at LIMIT 10"
                : "SELECT id,user_id FROM long_term_memory WHERE deleted_at IS NULL AND status='active' "
                  + "AND (next_review_at IS NULL OR next_review_at<=now()) LIMIT 500",
                vectors ? new Object[]{model} : new Object[]{});
    }

    /** 在内容未变时补建向量，避免后台结果覆盖用户刚编辑的事实。 */
    public void backfill(UUID owner, UUID id, String text, String vector, String model) {
        jdbc.update("UPDATE long_term_memory SET embedding=CAST(? AS vector),embedding_model=? "
                + "WHERE id=? AND user_id=? AND content=? AND deleted_at IS NULL AND status IN ('active','pending')",
                vector, model, id, owner, text);
    }

    /** 更新衰减状态；stale 立即清除向量，隔离期过后再物理删除。 */
    public void decay(UUID owner, UUID id, double score, boolean cold, boolean stale) {
        jdbc.update("UPDATE long_term_memory SET decay_score=?,cold=?,status=?,next_review_at=now()+interval '7 days',"
                + "updated_at=CASE WHEN ? THEN now() ELSE updated_at END,"
                + "embedding=CASE WHEN ? THEN NULL ELSE embedding END WHERE user_id=? AND id=?",
                score, cold, stale ? "stale" : "active", stale, stale, owner, id);
    }

    /** 过期项按用户事务删除纠偏审计和主体，不影响活动或永不衰减项。 */
    public void purgeStale(int retentionDays) {
        tx.executeWithoutResult(status -> {
            String condition = "status='stale' AND never_decay=false AND updated_at<now()-(? * interval '1 day')";
            jdbc.update("DELETE FROM memory_correction WHERE memory_id IN (SELECT id FROM long_term_memory WHERE "
                    + condition + ")", retentionDays);
            jdbc.update("DELETE FROM long_term_memory WHERE " + condition, retentionDays);
        });
    }
}
