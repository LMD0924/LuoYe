package com.luoye.service.todo.impl;

import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.ResultCode;
import com.luoye.dao.entity.Todo;
import com.luoye.dao.mapper.TodoMapper;
import com.luoye.service.todo.TodoService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TodoServiceImpl implements TodoService {

    private final TodoMapper todos;

    public TodoServiceImpl(TodoMapper todos) {
        this.todos = todos;
    }

    @Override
    public List<Todo> list(UUID owner, String status) {
        return todos.selectOwned(owner, status);
    }

    @Override
    public Todo get(UUID owner, UUID id) {
        Todo todo = todos.selectOwnedById(owner, id);
        if (todo == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "待办不存在");
        }
        return todo;
    }

    @Override
    public Todo create(UUID owner, String title, String description, Instant dueAt,
                       Instant remindAt, String origin) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待办标题不能为空");
        }
        Todo todo = new Todo();
        todo.setUserId(owner);
        todo.setTitle(title.trim());
        todo.setDescription(description);
        todo.setDueAt(dueAt);
        todo.setRemindAt(remindAt == null ? dueAt : remindAt);
        todo.setStatus("open");
        todo.setOriginText(origin);
        todos.insert(todo);
        return todos.selectOwnedById(owner, todo.getId());
    }

    @Override
    public Todo update(UUID owner, UUID id, String title, String description, Instant dueAt,
                       Instant remindAt, String status) {
        Todo todo = get(owner, id);
        if (title != null && !title.isBlank()) {
            todo.setTitle(title.trim());
        }
        if (description != null) {
            todo.setDescription(description);
        }
        if (dueAt != null) {
            todo.setDueAt(dueAt);
        }
        if (remindAt != null) {
            todo.setRemindAt(remindAt);
        }
        if ("done".equals(status) || "open".equals(status)) {
            todo.setStatus(status);
        }
        todo.setUpdatedAt(Instant.now());
        todos.updateById(todo);
        return todos.selectOwnedById(owner, id);
    }

    @Override
    public void delete(UUID owner, UUID id) {
        get(owner, id);
        todos.softDelete(owner, id);
    }

    @Override
    public List<Todo> dueReminders(UUID owner) {
        return todos.selectDueReminders(owner);
    }
}
