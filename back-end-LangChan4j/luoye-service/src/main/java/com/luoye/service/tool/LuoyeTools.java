package com.luoye.service.tool;

import com.luoye.dao.entity.Todo;
import com.luoye.dao.mapper.ToolCallMapper;
import com.luoye.service.config.UserSettingsService;
import com.luoye.service.memory.MemoryService;
import com.luoye.service.note.NoteService;
import com.luoye.service.support.RequestContext;
import com.luoye.service.todo.TodoService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** 对话可调用的工具：记忆、笔记、待办、联网搜索。 */
@Component
public class LuoyeTools {

    private final MemoryService memory;
    private final NoteService notes;
    private final TodoService todos;
    private final WebSearchService search;
    private final UserSettingsService settings;
    private final ToolCallMapper logs;

    public LuoyeTools(
            MemoryService memory,
            NoteService notes,
            TodoService todos,
            WebSearchService search,
            UserSettingsService settings,
            ToolCallMapper logs) {
        this.memory = memory;
        this.notes = notes;
        this.todos = todos;
        this.search = search;
        this.settings = settings;
        this.logs = logs;
    }

    @Tool("把用户明确要求记住的事实写入长期记忆")
    public String remember(@P("要记住的事实") String fact) {
        long start = System.currentTimeMillis();
        UUID user = RequestContext.userId();
        try {
            memory.remember(user, RequestContext.sessionId(), null, fact, "fact", "explicit", true);
            log("memoryRemember", fact, "ok", start);
            return "已记住：" + fact;
        } catch (RuntimeException e) {
            log("memoryRemember", fact, e.getMessage(), start);
            return "未能写入记忆：" + e.getMessage();
        }
    }

    @Tool("把对话内容整理成一篇 Markdown 笔记并加入知识库")
    public String saveNote(@P("笔记标题") String title, @P("笔记正文，Markdown") String content) {
        long start = System.currentTimeMillis();
        UUID user = RequestContext.userId();
        try {
            notes.save(user, null, title, content);
            log("noteSave", title, "ok", start);
            return "已保存笔记《" + title + "》";
        } catch (RuntimeException e) {
            log("noteSave", title, e.getMessage(), start);
            return "保存笔记失败：" + e.getMessage();
        }
    }

    @Tool("创建一条待办或提醒。dueAt 使用 ISO 日期时间，如 2026-10-01T15:00")
    public String createTodo(
            @P("待办标题") String title,
            @P("可选说明") String description,
            @P("截止或提醒时间，可空") String dueAt) {
        long start = System.currentTimeMillis();
        UUID user = RequestContext.userId();
        try {
            Instant due = parseTime(dueAt);
            Todo todo = todos.create(user, title, description, due, due, title);
            log("todosCreate", title, todo.getId().toString(), start);
            return "已创建待办：" + todo.getTitle()
                    + (due == null ? "" : "，时间 " + due);
        } catch (RuntimeException e) {
            log("todosCreate", title, e.getMessage(), start);
            return "创建待办失败：" + e.getMessage();
        }
    }

    @Tool("列出当前未完成的待办")
    public String listTodos() {
        UUID user = RequestContext.userId();
        List<Todo> open = todos.list(user, "open");
        if (open.isEmpty()) {
            return "目前没有未完成待办";
        }
        return open.stream()
                .map(item -> "- " + item.getTitle()
                        + (item.getDueAt() == null ? "" : "（" + item.getDueAt() + "）"))
                .collect(Collectors.joining("\n"));
    }

    @Tool("按需联网搜索最新公开信息，结果需向用户标明来源")
    public String webSearch(@P("搜索关键词") String query) {
        long start = System.currentTimeMillis();
        UUID user = RequestContext.userId();
        if (!settings.webEnabled(user)) {
            return "用户已关闭联网搜索。请基于已有知识回答，并说明未检索网络。";
        }
        String result = search.search(query);
        log("webSearch", query, result.length() > 500 ? result.substring(0, 500) : result, start);
        return result;
    }

    private void log(String tool, String input, String output, long start) {
        try {
            String in = "{\"text\":\"" + escape(input) + "\"}";
            String out = "{\"text\":\"" + escape(output) + "\"}";
            logs.insertLog(UUID.randomUUID(), RequestContext.userId(), RequestContext.sessionId(),
                    tool, in, out, "success", (int) (System.currentTimeMillis() - start));
        } catch (RuntimeException ignored) {
            /* 审计失败不影响工具结果。 */
        }
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            /* 继续尝试本地日期时间。 */
        }
        ZoneId zone = ZoneId.systemDefault();
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            /* 继续尝试仅日期。 */
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
