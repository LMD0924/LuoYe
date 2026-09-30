package com.luoye.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luoye.dao.entity.LongTermMemory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 长期记忆 Mapper。
 *
 * <p>基础 CRUD 由 {@link BaseMapper} 提供；向量检索、抽取写入、纠偏、
 * 衰减和批量维护为自定义 SQL。读方法统一不返回 embedding 大向量。
 */
@Mapper
public interface LongTermMemoryMapper extends BaseMapper<LongTermMemory> {

    /** 不含 embedding 的列清单，避免列表/检索时传输 1536 维向量。 */
    String COLS = "id,user_id,content,memory_type,source,confidence,status,importance_weight,"
            + "access_count,last_access_at,never_decay,correction_count,origin_session_id,"
            + "origin_message_id,next_review_at,content_hash,embedding_model,decay_score,cold,"
            + "created_at,updated_at,deleted_at";

    // ==================== 列表与单条 ====================

    /**
     * 按类型、状态、文本关键词分页过滤。
     *
     * @param owner 用户 ID
     * @param type 记忆类型（可空）
     * @param state 状态（可空）
     * @param q 正文包含的关键词（可空）
     * @param limit 每页条数
     * @param offset 偏移量
     * @return 按创建时间倒序的当前页记忆
     */
    @Select("<script>SELECT " + COLS + " FROM long_term_memory "
            + "WHERE user_id=#{owner} AND deleted_at IS NULL AND status &lt;&gt; 'deleted'"
            + "<if test='type != null and type != \"\"'> AND memory_type=#{type}</if>"
            + "<if test='state != null and state != \"\"'> AND status=#{state}</if>"
            + "<if test='q != null and q != \"\"'> AND strpos(lower(content),lower(#{q})) &gt; 0</if>"
            + " ORDER BY created_at DESC,id LIMIT #{limit} OFFSET #{offset}</script>")
    List<LongTermMemory> selectPage(@Param("owner") UUID owner,
                                    @Param("type") String type,
                                    @Param("state") String state,
                                    @Param("q") String q,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    /** @return 与 {@link #selectPage} 相同过滤条件下的总数，用于分页。 */
    @Select("<script>SELECT count(*) FROM long_term_memory "
            + "WHERE user_id=#{owner} AND deleted_at IS NULL AND status &lt;&gt; 'deleted'"
            + "<if test='type != null and type != \"\"'> AND memory_type=#{type}</if>"
            + "<if test='state != null and state != \"\"'> AND status=#{state}</if>"
            + "<if test='q != null and q != \"\"'> AND strpos(lower(content),lower(#{q})) &gt; 0</if>"
            + "</script>")
    long countPage(@Param("owner") UUID owner,
                   @Param("type") String type,
                   @Param("state") String state,
                   @Param("q") String q);

    /**
     * 读取单条未删除记忆；其他用户的 UUID 视为不存在，调用方在为空时抛 404。
     */
    @Select("SELECT " + COLS + " FROM long_term_memory "
            + "WHERE user_id=#{owner} AND id=#{id} AND deleted_at IS NULL AND status <> 'deleted'")
    LongTermMemory selectOwned(@Param("owner") UUID owner, @Param("id") UUID id);

    /**
     * @return 供抽取判断纠偏目标的有限快照（active/pending），按最近变更倒序。
     */
    @Select("SELECT " + COLS + " FROM long_term_memory "
            + "WHERE user_id=#{owner} AND deleted_at IS NULL AND status IN ('active','pending') "
            + "ORDER BY coalesce(updated_at,created_at) DESC LIMIT 100")
    List<LongTermMemory> selectCandidates(@Param("owner") UUID owner);

    /**
     * @param hash 正文哈希
     * @return 与哈希相同的活动或待确认记忆（去重用）
     */
    @Select("SELECT " + COLS + " FROM long_term_memory "
            + "WHERE user_id=#{owner} AND content_hash=#{hash} AND deleted_at IS NULL "
            + "AND status IN ('active','pending') LIMIT 1")
    LongTermMemory selectByHash(@Param("owner") UUID owner, @Param("hash") String hash);

    // ==================== 检索 ====================

    /**
     * 活动记忆的余弦近邻；模型标识隔离避免混用不同嵌入空间。
     *
     * @param vector 查询向量文本，如 "[0.1,0.2,...]"
     * @param maxDistance 最大余弦距离（= 1 - 相似度阈值）
     */
    @Select("SELECT " + COLS + " FROM long_term_memory "
            + "WHERE user_id=#{owner} AND status='active' AND deleted_at IS NULL "
            + "AND embedding_model=#{model} AND embedding IS NOT NULL "
            + "AND (embedding <=> CAST(#{vector} AS vector)) <= #{maxDistance} "
            + "ORDER BY embedding <=> CAST(#{vector} AS vector) LIMIT #{limit}")
    List<LongTermMemory> selectNearest(@Param("owner") UUID owner,
                                       @Param("vector") String vector,
                                       @Param("model") String model,
                                       @Param("limit") int limit,
                                       @Param("maxDistance") double maxDistance);

    /**
     * 中文关键词与 FTS/trigram 降级检索；不向已删除/待确认记忆放宽过滤。
     *
     * @param keyword 非空时按关键词子串匹配，否则用 query 本身
     */
    @Select("SELECT " + COLS + " FROM long_term_memory "
            + "WHERE user_id=#{owner} AND status='active' AND deleted_at IS NULL AND ("
            + "strpos(lower(content),lower(#{keyword}))>0 "
            + "OR to_tsvector('simple',content) @@ plainto_tsquery('simple',#{query}) "
            + "OR similarity(content,#{query})>0.08) "
            + "ORDER BY similarity(content,#{query}) DESC,updated_at DESC LIMIT #{limit}")
    List<LongTermMemory> searchByText(@Param("owner") UUID owner,
                                      @Param("query") String query,
                                      @Param("keyword") String keyword,
                                      @Param("limit") int limit);

    /**
     * @return 常驻热区记忆（永不衰减，或高重要度且多次访问），每次实时读取不缓存。
     */
    @Select("SELECT " + COLS + " FROM long_term_memory "
            + "WHERE user_id=#{owner} AND status='active' AND deleted_at IS NULL AND cold=false "
            + "AND (never_decay=true OR (importance_weight>=0.7 AND access_count>=3)) "
            + "ORDER BY never_decay DESC,access_count DESC LIMIT #{limit}")
    List<LongTermMemory> selectHot(@Param("owner") UUID owner, @Param("limit") int limit);

    // ==================== 写入与状态变更 ====================

    /**
     * 插入抽取结果；向量失败时 vector/model 传 null，由补建任务重试。
     * next_review_at 默认 7 天后。
     */
    @Insert("INSERT INTO long_term_memory(id,user_id,content,memory_type,source,confidence,status,"
            + "importance_weight,access_count,never_decay,origin_session_id,origin_message_id,"
            + "content_hash,embedding,embedding_model,decay_score,cold,updated_at,next_review_at) "
            + "VALUES(#{id},#{owner},#{content},#{type},#{source},#{confidence},#{status},"
            + "0.5,0,#{neverDecay},#{sessionId},#{messageId},#{hash},"
            + "CAST(#{vector} AS vector),#{embeddingModel},1,false,now(),now()+interval '7 days')")
    int insertMemory(@Param("id") UUID id,
                     @Param("owner") UUID owner,
                     @Param("content") String content,
                     @Param("type") String type,
                     @Param("source") String source,
                     @Param("confidence") String confidence,
                     @Param("status") String status,
                     @Param("neverDecay") boolean neverDecay,
                     @Param("sessionId") UUID sessionId,
                     @Param("messageId") UUID messageId,
                     @Param("hash") String hash,
                     @Param("vector") String vector,
                     @Param("embeddingModel") String embeddingModel);

    /**
     * 用户纠偏：替换文本与向量并重置生命周期；失败嵌入清空旧向量，不继续召回旧事实。
     * 纠偏审计记录由 service 层通过 MemoryCorrectionMapper 另行插入。
     */
    @Update("UPDATE long_term_memory SET content=#{text},content_hash=#{hash},"
            + "embedding=CAST(#{vector} AS vector),embedding_model=#{embeddingModel},"
            + "source='explicit',confidence='high',status='active',"
            + "correction_count=correction_count+1,never_decay=#{neverDecay},updated_at=now(),"
            + "cold=false,decay_score=1,next_review_at=now()+interval '7 days' "
            + "WHERE user_id=#{owner} AND id=#{id} AND deleted_at IS NULL")
    int correct(@Param("owner") UUID owner,
                @Param("id") UUID id,
                @Param("text") String text,
                @Param("hash") String hash,
                @Param("vector") String vector,
                @Param("embeddingModel") String embeddingModel,
                @Param("neverDecay") boolean neverDecay);

    /** 软删除并立即清除向量；调用者需在同一事务登记抑制哈希。 */
    @Update("UPDATE long_term_memory SET status='deleted',deleted_at=now(),updated_at=now(),"
            + "embedding=NULL,embedding_model=NULL WHERE user_id=#{owner} AND id=#{id}")
    int softDelete(@Param("owner") UUID owner, @Param("id") UUID id);

    /** 确认 pending 项，确认后按用户认可事实处理。 */
    @Update("UPDATE long_term_memory SET status='active',confidence='high',source='explicit',"
            + "updated_at=now() WHERE user_id=#{owner} AND id=#{id} AND status='pending'")
    int confirm(@Param("owner") UUID owner, @Param("id") UUID id);

    /** 记录实际送入模型的访问量。 */
    @Update("UPDATE long_term_memory SET access_count=access_count+1,last_access_at=now() "
            + "WHERE user_id=#{owner} AND id=#{id} AND status='active' AND deleted_at IS NULL")
    int touch(@Param("owner") UUID owner, @Param("id") UUID id);

    /**
     * 在内容未变时补建向量，避免后台结果覆盖用户刚编辑的事实。
     */
    @Update("UPDATE long_term_memory SET embedding=CAST(#{vector} AS vector),"
            + "embedding_model=#{model} WHERE id=#{id} AND user_id=#{owner} AND content=#{text} "
            + "AND deleted_at IS NULL AND status IN ('active','pending')")
    int backfill(@Param("owner") UUID owner,
                 @Param("id") UUID id,
                 @Param("text") String text,
                 @Param("vector") String vector,
                 @Param("model") String model);

    /**
     * 更新衰减状态；stale 时立即清除向量，复查时间顺延 7 天。
     *
     * @param status stale 时传 "stale"，否则传 "active"
     */
    @Update("UPDATE long_term_memory SET decay_score=#{score},cold=#{cold},status=#{status},"
            + "next_review_at=now()+interval '7 days',"
            + "updated_at=CASE WHEN #{stale} THEN now() ELSE updated_at END,"
            + "embedding=CASE WHEN #{stale} THEN NULL ELSE embedding END "
            + "WHERE user_id=#{owner} AND id=#{id}")
    int updateDecay(@Param("owner") UUID owner,
                    @Param("id") UUID id,
                    @Param("score") double score,
                    @Param("cold") boolean cold,
                    @Param("status") String status,
                    @Param("stale") boolean stale);

    // ==================== 批量维护 ====================

    /** @return 缺向量或向量模型不匹配的记忆（限 10 条），供向量补建任务。 */
    @Select("SELECT id,user_id FROM long_term_memory WHERE deleted_at IS NULL "
            + "AND status IN ('active','pending') "
            + "AND (embedding IS NULL OR embedding_model IS DISTINCT FROM #{model}) "
            + "ORDER BY created_at LIMIT 10")
    List<Map<String, Object>> vectorBackfillTargets(@Param("model") String model);

    /** @return 到期待复查的活动记忆（限 500 条），供衰减任务。 */
    @Select("SELECT id,user_id FROM long_term_memory WHERE deleted_at IS NULL AND status='active' "
            + "AND (next_review_at IS NULL OR next_review_at<=now()) LIMIT 500")
    List<Map<String, Object>> decayReviewTargets();

    /** 物理清理前先删除 stale 记忆对应的纠偏审计。 */
    @Delete("DELETE FROM memory_correction WHERE memory_id IN ("
            + "SELECT id FROM long_term_memory WHERE status='stale' AND never_decay=false "
            + "AND updated_at<now()-(#{retentionDays} * interval '1 day'))")
    int deleteCorrectionsOfStale(@Param("retentionDays") int retentionDays);

    /** 物理删除超过保留期的 stale 记忆；活动或永不衰减项不受影响。 */
    @Delete("DELETE FROM long_term_memory WHERE status='stale' AND never_decay=false "
            + "AND updated_at<now()-(#{retentionDays} * interval '1 day')")
    int purgeStale(@Param("retentionDays") int retentionDays);
}
