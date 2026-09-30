package com.luoye.service.embed;

import com.luoye.service.support.TextSupport;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 嵌入调用封装：失败时返回空向量，由检索走全文检索降级。
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel model;
    private final int dimension;
    private final String modelName;

    public EmbeddingService(
            EmbeddingModel model,
            @Value("${langchain4j.embedding.dimension:1536}") int dimension,
            @Value("${langchain4j.embedding.model:}") String modelName) {
        this.model = model;
        this.dimension = dimension;
        this.modelName = modelName == null || modelName.isBlank() ? "default" : modelName;
    }

    public String modelName() {
        return modelName;
    }

    public int dimension() {
        return dimension;
    }

    /** @return pgvector 文本；嵌入失败返回 null */
    public String embedOrNull(String text) {
        float[] vector = embedArray(text);
        return vector == null ? null : TextSupport.toPgVector(vector);
    }

    public float[] embedArray(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Response<Embedding> response = model.embed(text);
            if (response == null || response.content() == null) {
                return null;
            }
            return TextSupport.fit(response.content().vector(), dimension);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
