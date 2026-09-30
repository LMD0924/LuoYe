package com.luoye.service.todo;

import com.luoye.dao.entity.Todo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 待办 CRUD。 */
public interface TodoService {

    List<Todo> list(UUID owner, String status);

    Todo get(UUID owner, UUID id);

    Todo create(UUID owner, String title, String description, Instant dueAt, Instant remindAt, String origin);

    Todo update(UUID owner, UUID id, String title, String description, Instant dueAt,
                Instant remindAt, String status);

    void delete(UUID owner, UUID id);

    List<Todo> dueReminders(UUID owner);
}
