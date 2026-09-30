package com.luoye.service.memory.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.ResultCode;
import com.luoye.dao.entity.LongTermMemory;
import com.luoye.dao.entity.MemoryCorrection;
import com.luoye.dao.entity.Message;
import com.luoye.dao.mapper.LongTermMemoryMapper;
import com.luoye.dao.mapper.MemoryCorrectionMapper;
import com.luoye.dao.mapper.MemoryExtractionCursorMapper;
import com.luoye.dao.mapper.MemorySuppressionMapper;
import com.luoye.dao.mapper.MessageMapper;
import com.luoye.dao.mapper.UserMapper;
import com.luoye.service.embed.EmbeddingService;
import com.luoye.service.memory.MemoryService;
import com.luoye.service.support.TextSupport;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 长期记忆服务：向量召回失败时走全文检索；抽取失败时仅保留显式规则命中。
 */
@Service
public class MemoryServiceImpl implements MemoryService {

    private static final Pattern EXPLICIT = Pattern.compile(
            "(记住|别忘了|记得|我叫|我是|我住|我喜欢|我生日|我的名字)");
    private static final int HOT = 5;
    private static final int TOP_K = 5;

    private final LongTermMemoryMapper memories;
    private final MemorySuppressionMapper suppressions;
    private final MemoryCorrectionMapper corrections;
    private final MemoryExtractionCursorMapper cursors;
    private final MessageMapper messages;
    private final UserMapper users;
    private final EmbeddingService embeddings;
    private final ChatLanguageModel chatModel;
    private final ObjectMapper mapper;

    public MemoryServiceImpl(
            LongTermMemoryMapper memories,
            MemorySuppressionMapper suppressions,
            MemoryCorrectionMapper corrections,
            MemoryExtractionCursorMapper cursors,
            MessageMapper messages,
            UserMapper users,
            EmbeddingService embeddings,
            ChatLanguageModel chatModel,
            ObjectMapper mapper) {
        this.memories = memories;
        this.suppressions = suppressions;
        this.corrections = corrections;
        this.cursors = cursors;
        this.messages = messages;
        this.users = users;
        this.embeddings = embeddings;
        this.chatModel = chatModel;
        this.mapper = mapper;
    }

    @Override
    public Page list(UUID owner, String type, String status, String q, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        return new Page(
                memories.selectPage(owner, emptyToNull(type), emptyToNull(status), emptyToNull(q),
                        limit, offset),
                memories.countPage(owner, emptyToNull(type), emptyToNull(status), emptyToNull(q)));
    }

    @Override
    public LongTermMemory get(UUID owner, UUID id) {
        LongTermMemory row = memories.selectOwned(owner, id);
        if (row == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "记忆不存在");
        }
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LongTermMemory remember(UUID owner, UUID sessionId, UUID messageId, String content,
                                   String type, String source, boolean neverDecay) {
        String text = requireText(content);
        if (TextSupport.sensitive(text)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "敏感信息不会写入长期记忆");
        }
        String hash = TextSupport.sha256(text);
        if (suppressions.countSuppressed(owner, hash) > 0 && !"explicit".equals(source)) {
            return memories.selectByHash(owner, hash);
        }
        LongTermMemory existing = memories.selectByHash(owner, hash);
        if (existing != null) {
            memories.touch(owner, existing.getId());
            return memories.selectOwned(owner, existing.getId());
        }
        UUID id = UUID.randomUUID();
        String confidence = "explicit".equals(source) ? "high" : "medium";
        String status = "low".equals(confidence) ? "pending" : "active";
        if ("explicit".equals(source)) {
            status = "active";
            confidence = "high";
        }
        users.selectRevisionForUpdate(owner);
        memories.insertMemory(
                id, owner, text, normalizeType(type), source, confidence, status, neverDecay,
                sessionId, messageId, hash, embeddings.embedOrNull(text), embeddings.modelName());
        users.incrementRevision(owner);
        return memories.selectOwned(owner, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LongTermMemory correct(UUID owner, UUID id, String content, boolean neverDecay) {
        LongTermMemory current = get(owner, id);
        String text = requireText(content);
        String hash = TextSupport.sha256(text);
        users.selectRevisionForUpdate(owner);
        memories.correct(owner, id, text, hash, embeddings.embedOrNull(text),
                embeddings.modelName(), neverDecay);
        MemoryCorrection log = new MemoryCorrection();
        log.setUserId(owner);
        log.setMemoryId(id);
        log.setCorrectedValue(text);
        corrections.insert(log);
        if (current.getContent() != null) {
            suppressions.suppress(owner, TextSupport.sha256(current.getContent()));
        }
        users.incrementRevision(owner);
        return get(owner, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID owner, UUID id) {
        LongTermMemory current = get(owner, id);
        users.selectRevisionForUpdate(owner);
        memories.softDelete(owner, id);
        suppressions.suppress(owner, TextSupport.sha256(current.getContent()));
        users.incrementRevision(owner);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int confirm(UUID owner, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (UUID id : ids) {
            count += memories.confirm(owner, id);
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearAll(UUID owner) {
        users.selectRevisionForUpdate(owner);
        List<LongTermMemory> rows = memories.selectPage(owner, null, null, null, 5000, 0);
        int count = 0;
        for (LongTermMemory row : rows) {
            memories.softDelete(owner, row.getId());
            suppressions.suppress(owner, TextSupport.sha256(row.getContent()));
            count++;
        }
        cursors.skipHistory(owner);
        users.incrementRevision(owner);
        return count;
    }

    @Override
    public Recall recall(UUID owner, String query) {
        List<LongTermMemory> hot = memories.selectHot(owner, HOT);
        List<LongTermMemory> hits = new ArrayList<>();
        String reason = "topk_rank";
        boolean retrieved = true;
        String vector = embeddings.embedOrNull(query);
        if (vector != null) {
            hits.addAll(memories.selectNearest(owner, vector, embeddings.modelName(), TOP_K, 0.45));
        } else {
            hits.addAll(memories.searchByText(owner, query, firstKeyword(query), TOP_K));
            reason = "fts_fallback";
        }
        Set<UUID> seen = new LinkedHashSet<>();
        List<LongTermMemory> merged = new ArrayList<>();
        for (LongTermMemory row : concat(hot, hits)) {
            if (row == null || !seen.add(row.getId())) {
                continue;
            }
            merged.add(row);
            memories.touch(owner, row.getId());
        }
        if (merged.isEmpty()) {
            return new Recall("（已检索，未命中长期记忆）",
                    Map.of("retrieved", true, "hits", 0, "reason", reason),
                    List.of());
        }
        StringBuilder context = new StringBuilder("你记得关于用户的这些事实：\n");
        List<Map<String, Object>> citations = new ArrayList<>();
        for (LongTermMemory row : merged) {
            context.append("- ").append(row.getContent()).append('\n');
            citations.add(Map.of(
                    "kind", "memory",
                    "id", row.getId().toString(),
                    "title", row.getMemoryType() == null ? "fact" : row.getMemoryType(),
                    "snippet", row.getContent()));
        }
        return new Recall(context.toString().trim(),
                Map.of("retrieved", retrieved, "hits", merged.size(), "reason", reason),
                citations);
    }

    @Override
    public void extractAfterTurn(UUID owner, UUID sessionId, String userText) {
        if (userText != null && EXPLICIT.matcher(userText).find() && !TextSupport.sensitive(userText)) {
            try {
                remember(owner, sessionId, null, compactFact(userText), "fact", "explicit", true);
            } catch (RuntimeException ignored) {
                /* 显式写入失败不影响对话主路径。 */
            }
        }
        List<Message> pending = messages.selectUnprocessedUserMessages(sessionId);
        if (pending.isEmpty()) {
            return;
        }
        int lastSeq = pending.stream().mapToInt(Message::getSeq).max().orElse(0);
        try {
            extractWithModel(owner, sessionId, pending);
        } catch (RuntimeException ignored) {
            /* 模型抽取失败时仍推进游标，避免同一批反复消耗。 */
        }
        cursors.checkpoint(sessionId, lastSeq);
    }

    @Override
    public void backfillEmbeddings() {
        for (Map<String, Object> row : memories.vectorBackfillTargets(embeddings.modelName())) {
            UUID id = asUuid(row.get("id"));
            UUID owner = asUuid(row.get("user_id"));
            if (id == null || owner == null) {
                continue;
            }
            LongTermMemory memory = memories.selectOwned(owner, id);
            if (memory == null) {
                continue;
            }
            String vector = embeddings.embedOrNull(memory.getContent());
            if (vector != null) {
                memories.backfill(owner, id, memory.getContent(), vector, embeddings.modelName());
            }
        }
    }

    @Override
    public void decayAndPurge() {
        Instant now = Instant.now();
        for (Map<String, Object> row : memories.decayReviewTargets()) {
            UUID id = asUuid(row.get("id"));
            UUID owner = asUuid(row.get("user_id"));
            if (id == null || owner == null) {
                continue;
            }
            LongTermMemory memory = memories.selectOwned(owner, id);
            if (memory == null || Boolean.TRUE.equals(memory.getNeverDecay())) {
                continue;
            }
            double recency = Math.exp(-Duration.between(
                    memory.getLastAccessAt() == null ? memory.getCreatedAt() : memory.getLastAccessAt(),
                    now).toDays() / 90.0);
            double frequency = Math.log(1 + (memory.getAccessCount() == null ? 0 : memory.getAccessCount()))
                    / Math.log(1 + 20);
            double importance = memory.getImportanceWeight() == null ? 0.5 : memory.getImportanceWeight();
            double score = 0.5 * importance + 0.3 * recency + 0.2 * frequency;
            boolean stale = score <= 0.18;
            boolean cold = !stale && score < 0.45;
            memories.updateDecay(owner, id, score, cold, stale ? "stale" : "active", stale);
        }
        memories.deleteCorrectionsOfStale(30);
        memories.purgeStale(30);
    }

    private void extractWithModel(UUID owner, UUID sessionId, List<Message> pending) {
        StringBuilder transcript = new StringBuilder();
        for (Message message : pending) {
            transcript.append("- ").append(message.getContent()).append('\n');
        }
        String prompt = """
                从用户原话抽取值得长期记住的稳定事实。只抽取关于用户本人的偏好、身份、地点或重要事件。
                不要抽取一次性情绪或敏感信息。返回 JSON 数组，元素字段：
                content, type(fact|preference|event|profile), source(explicit|inferred),
                confidence(high|medium|low), action(upsert|forget)。
                无事实则返回 []。
                用户原话：
                %s
                """.formatted(transcript);
        String raw = chatModel.generate(prompt);
        JsonNode array = parseArray(raw);
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            String content = text(node, "content");
            if (content.isBlank() || TextSupport.sensitive(content)) {
                continue;
            }
            String action = text(node, "action");
            if ("forget".equalsIgnoreCase(action)) {
                LongTermMemory existing = memories.selectByHash(owner, TextSupport.sha256(content));
                if (existing != null) {
                    delete(owner, existing.getId());
                }
                continue;
            }
            String source = "explicit".equalsIgnoreCase(text(node, "source")) ? "explicit" : "inferred";
            String confidence = normalizeConfidence(text(node, "confidence"));
            String status = "low".equals(confidence) ? "pending" : "active";
            if ("explicit".equals(source)) {
                confidence = "high";
                status = "active";
            }
            String hash = TextSupport.sha256(content);
            if (suppressions.countSuppressed(owner, hash) > 0) {
                continue;
            }
            if (memories.selectByHash(owner, hash) != null) {
                continue;
            }
            UUID id = UUID.randomUUID();
            memories.insertMemory(
                    id, owner, content, normalizeType(text(node, "type")), source, confidence, status,
                    "explicit".equals(source), sessionId, pending.get(0).getId(), hash,
                    embeddings.embedOrNull(content), embeddings.modelName());
        }
    }

    private JsonNode parseArray(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(trimmed.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String compactFact(String userText) {
        String text = userText.trim();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static String requireText(String content) {
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "记忆内容不能为空且不超过 2000 字");
        }
        return content.trim();
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "fact";
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "preference", "event", "profile" -> type.toLowerCase(Locale.ROOT);
            default -> "fact";
        };
    }

    private static String normalizeConfidence(String value) {
        if ("low".equalsIgnoreCase(value) || "medium".equalsIgnoreCase(value)) {
            return value.toLowerCase(Locale.ROOT);
        }
        return "high";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstKeyword(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return query.length() <= 8 ? query : query.substring(0, 8);
    }

    private static List<LongTermMemory> concat(List<LongTermMemory> a, List<LongTermMemory> b) {
        List<LongTermMemory> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static UUID asUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return value == null ? null : UUID.fromString(value.toString());
    }
}
