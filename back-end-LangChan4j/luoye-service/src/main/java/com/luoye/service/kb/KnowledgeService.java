package com.luoye.service.kb;

import com.luoye.dao.entity.KbChunk;
import com.luoye.dao.entity.KbDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 知识库入库与检索。 */
public interface KnowledgeService {

    record Recall(String context, Map<String, Object> log, List<Map<String, Object>> citations) {
        public static Recall empty() {
            return new Recall("（本轮未检索知识库）",
                    Map.of("retrieved", false, "hits", 0, "reason", "skipped"),
                    List.of());
        }
    }

    List<KbDocument> list(UUID owner);

    KbDocument get(UUID owner, UUID id);

    KbDocument upload(UUID owner, MultipartFile file);

    void delete(UUID owner, UUID id);

    List<KbChunk> search(UUID owner, String query);

    Recall retrieve(UUID owner, String query);

    UUID indexText(UUID owner, String title, String sourceType, UUID sourceRef, String text);
}
