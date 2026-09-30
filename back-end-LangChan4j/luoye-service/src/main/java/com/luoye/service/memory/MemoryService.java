package com.luoye.service.memory;

import com.luoye.dao.entity.LongTermMemory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 长期记忆：召回、抽取、管理、衰减。 */
public interface MemoryService {

    record Page(List<LongTermMemory> items, long total) {
    }

    record Recall(String context, Map<String, Object> log, List<Map<String, Object>> citations) {
        public static Recall empty() {
            return new Recall("（本轮未检索长期记忆）",
                    Map.of("retrieved", false, "hits", 0, "reason", "disabled"),
                    List.of());
        }
    }

    Page list(UUID owner, String type, String status, String q, int page, int size);

    LongTermMemory get(UUID owner, UUID id);

    LongTermMemory remember(UUID owner, UUID sessionId, UUID messageId, String content,
                            String type, String source, boolean neverDecay);

    LongTermMemory correct(UUID owner, UUID id, String content, boolean neverDecay);

    void delete(UUID owner, UUID id);

    int confirm(UUID owner, List<UUID> ids);

    int clearAll(UUID owner);

    Recall recall(UUID owner, String query);

    void extractAfterTurn(UUID owner, UUID sessionId, String userText);

    void backfillEmbeddings();

    void decayAndPurge();
}
