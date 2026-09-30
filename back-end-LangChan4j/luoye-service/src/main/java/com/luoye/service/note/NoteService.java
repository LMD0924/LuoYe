package com.luoye.service.note;

import com.luoye.dao.entity.Note;

import java.util.List;
import java.util.UUID;

/** 笔记 CRUD，保存后同步知识库。 */
public interface NoteService {

    List<Note> list(UUID owner);

    Note get(UUID owner, UUID id);

    Note save(UUID owner, UUID id, String title, String content);

    void delete(UUID owner, UUID id);
}
