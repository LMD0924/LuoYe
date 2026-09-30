package com.luoye.service.kb.impl;

import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.ResultCode;
import com.luoye.dao.entity.KbChunk;
import com.luoye.dao.entity.KbDocument;
import com.luoye.dao.mapper.KbChunkMapper;
import com.luoye.dao.mapper.KbDocumentMapper;
import com.luoye.service.embed.EmbeddingService;
import com.luoye.service.kb.KnowledgeService;
import com.luoye.service.support.TextSupport;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 文档解析、分块嵌入与 RAG 检索。 */
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private final KbDocumentMapper documents;
    private final KbChunkMapper chunks;
    private final EmbeddingService embeddings;
    private final DocumentSplitter splitter = DocumentSplitters.recursive(800, 120);

    public KnowledgeServiceImpl(
            KbDocumentMapper documents,
            KbChunkMapper chunks,
            EmbeddingService embeddings) {
        this.documents = documents;
        this.chunks = chunks;
        this.embeddings = embeddings;
    }

    @Override
    public List<KbDocument> list(UUID owner) {
        return documents.selectOwned(owner);
    }

    @Override
    public KbDocument get(UUID owner, UUID id) {
        KbDocument document = documents.selectOwnedById(owner, id);
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在");
        }
        return document;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KbDocument upload(UUID owner, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择 txt、md 或 pdf 文件");
        }
        String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".pdf"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持 txt / md / pdf");
        }
        String text = read(file, lower);
        if (text.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件没有可提取的文本");
        }
        UUID documentId = indexText(owner, stripExt(name), "upload", null, text);
        KbDocument document = documents.selectOwnedById(owner, documentId);
        document.setFileName(name);
        document.setMime(file.getContentType());
        documents.updateById(document);
        return documents.selectOwnedById(owner, documentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID owner, UUID id) {
        get(owner, id);
        chunks.deleteByDocument(id);
        documents.softDelete(owner, id);
    }

    @Override
    public List<KbChunk> search(UUID owner, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String vector = embeddings.embedOrNull(query);
        if (vector != null) {
            List<KbChunk> hits = chunks.selectNearest(owner, vector, embeddings.modelName(), 12, 0.5);
            if (!hits.isEmpty()) {
                return hits;
            }
        }
        return chunks.searchByText(owner, query, 12);
    }

    @Override
    public Recall retrieve(UUID owner, String query) {
        List<KbChunk> hits = search(owner, query);
        if (hits.isEmpty()) {
            return new Recall("（已检索知识库，未命中）",
                    Map.of("retrieved", true, "hits", 0, "reason", "topk_rank"),
                    List.of());
        }
        StringBuilder context = new StringBuilder("个人知识库摘录：\n");
        List<Map<String, Object>> citations = new ArrayList<>();
        int seq = 1;
        for (KbChunk chunk : hits.subList(0, Math.min(hits.size(), 6))) {
            context.append('[').append(seq).append("] ").append(chunk.getContent()).append("\n\n");
            citations.add(Map.of(
                    "kind", "kb",
                    "id", chunk.getDocumentId().toString(),
                    "chunkId", chunk.getId().toString(),
                    "title", "知识库",
                    "snippet", snippet(chunk.getContent())));
            seq++;
        }
        return new Recall(context.toString().trim(),
                Map.of("retrieved", true, "hits", hits.size(), "reason", "topk_rank"),
                citations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UUID indexText(UUID owner, String title, String sourceType, UUID sourceRef, String text) {
        KbDocument document = "note".equals(sourceType) && sourceRef != null
                ? documents.selectByNote(owner, sourceRef)
                : null;
        if (document == null) {
            document = new KbDocument();
            document.setUserId(owner);
            document.setTitle(title == null || title.isBlank() ? "未命名" : title);
            document.setSourceType(sourceType);
            document.setSourceRef(sourceRef);
            document.setStatus("indexing");
            document.setChunkCount(0);
            documents.insert(document);
        } else {
            document.setTitle(title == null || title.isBlank() ? document.getTitle() : title);
            document.setStatus("indexing");
            documents.updateById(document);
            chunks.deleteByDocument(document.getId());
        }
        List<TextSegment> segments = splitter.split(Document.from(text));
        int seq = 1;
        for (TextSegment segment : segments) {
            String content = segment.text();
            if (content == null || content.isBlank()) {
                continue;
            }
            chunks.insertChunk(
                    UUID.randomUUID(),
                    document.getId(),
                    owner,
                    seq++,
                    content,
                    TextSupport.sha256(content),
                    embeddings.embedOrNull(content),
                    Math.max(1, content.length() / 4),
                    null,
                    embeddings.modelName());
        }
        document.setChunkCount(seq - 1);
        document.setStatus(seq > 1 ? "ready" : "failed");
        documents.updateById(document);
        return document.getId();
    }

    private static String read(MultipartFile file, String lower) {
        try (InputStream input = file.getInputStream()) {
            if (lower.endsWith(".pdf")) {
                try (PDDocument pdf = PDDocument.load(input)) {
                    return new PDFTextStripper().getText(pdf);
                }
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法解析该文件");
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > 160 ? content.substring(0, 160) + "…" : content;
    }
}
