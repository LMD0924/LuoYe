package com.luoye.service.note.impl;

import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.ResultCode;
import com.luoye.dao.entity.KbDocument;
import com.luoye.dao.entity.Note;
import com.luoye.dao.mapper.KbChunkMapper;
import com.luoye.dao.mapper.KbDocumentMapper;
import com.luoye.dao.mapper.NoteMapper;
import com.luoye.service.kb.KnowledgeService;
import com.luoye.service.note.NoteService;
import com.luoye.service.support.TextSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NoteServiceImpl implements NoteService {

    private final NoteMapper notes;
    private final KbDocumentMapper documents;
    private final KbChunkMapper chunks;
    private final KnowledgeService knowledge;

    public NoteServiceImpl(
            NoteMapper notes,
            KbDocumentMapper documents,
            KbChunkMapper chunks,
            KnowledgeService knowledge) {
        this.notes = notes;
        this.documents = documents;
        this.chunks = chunks;
        this.knowledge = knowledge;
    }

    @Override
    public List<Note> list(UUID owner) {
        return notes.selectOwned(owner);
    }

    @Override
    public Note get(UUID owner, UUID id) {
        Note note = notes.selectOwnedById(owner, id);
        if (note == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "笔记不存在");
        }
        return note;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Note save(UUID owner, UUID id, String title, String content) {
        String safeTitle = title == null || title.isBlank() ? "未命名笔记" : title.trim();
        String body = content == null ? "" : content;
        String hash = TextSupport.sha256(body);
        Note note;
        if (id == null) {
            note = new Note();
            note.setUserId(owner);
            note.setTitle(safeTitle);
            note.setContent(body);
            note.setContentHash(hash);
            note.setStatus("active");
            notes.insert(note);
        } else {
            note = get(owner, id);
            if (hash.equals(note.getContentHash()) && safeTitle.equals(note.getTitle())) {
                return note;
            }
            note.setTitle(safeTitle);
            note.setContent(body);
            note.setContentHash(hash);
            note.setUpdatedAt(Instant.now());
            notes.updateById(note);
        }
        UUID documentId = knowledge.indexText(owner, safeTitle, "note", note.getId(), body);
        note.setKbDocumentId(documentId);
        notes.updateById(note);
        return notes.selectOwnedById(owner, note.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID owner, UUID id) {
        Note note = get(owner, id);
        if (note.getKbDocumentId() != null) {
            chunks.deleteByDocument(note.getKbDocumentId());
            documents.softDelete(owner, note.getKbDocumentId());
        }
        KbDocument linked = documents.selectByNote(owner, id);
        if (linked != null) {
            chunks.deleteByDocument(linked.getId());
            documents.softDelete(owner, linked.getId());
        }
        notes.softDelete(owner, id);
    }
}
