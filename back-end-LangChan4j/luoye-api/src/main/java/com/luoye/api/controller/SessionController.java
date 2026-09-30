package com.luoye.api.controller;

import java.util.*;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.luoye.api.config.JwtService;
import com.luoye.common.ApiException;
import com.luoye.dao.repository.ChatRepository;
import com.luoye.service.chat.ChatService;

/**
 * M1 会话与 SSE 接口，依赖 ChatRepository 和 ChatService。
 *
 * <p>从已认证身份读取用户 UUID，创建或查询会话，并把生成事件写入 SSE 响应。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

    /** 新会话请求；title 为空时由前端显示“未命名会话”。 */
    public record CreateSessionRequest(String title) {}

    /** 消息请求；stream 和 regenerateOf 目前只保留字段，尚未参与控制流。 */
    public record SendMessageRequest(String content, Boolean stream, UUID regenerateOf) {}

    private final ChatRepository repo;
    private final ChatService chat;

    /** @param repo 会话仓储 @param chat 流式生成服务 */
    public SessionController(ChatRepository repo, ChatService chat) {
        this.repo = repo;
        this.chat = chat;
    }

    /** 从认证上下文提取用户 UUID；身份异常转换为业务认证错误。 */
    private UUID owner(Authentication auth) {
        try {
            return UUID.fromString(auth.getName());
        } catch (Exception e) {
            throw new ApiException(401, "unauthorized", "无效的认证信息");
        }
    }

    /** @param auth 当前认证身份 @return 当前用户的会话列表 */
    @GetMapping("/sessions")
    public List<ChatRepository.Session> sessions(Authentication auth) {
        return repo.sessions(owner(auth));
    }

    /**
     * 创建会话并立即持久化。
     *
     * @param auth 当前认证身份
     * @param request 可省略的标题请求
     * @return 新建会话
     */
    @PostMapping("/sessions")
    public ChatRepository.Session create(
            Authentication auth, @RequestBody(required = false) CreateSessionRequest request) {
        return repo.createSession(owner(auth), request == null ? null : request.title());
    }

    /**
     * @param auth 当前认证身份
     * @param id 会话 UUID
     * @return 按 seq 正序排列的历史消息；会话归属在仓储中校验
     */
    @GetMapping("/sessions/{id}/messages")
    public List<ChatRepository.Message> messages(Authentication auth, @PathVariable UUID id) {
        return repo.history(owner(auth), id);
    }

    /**
     * 接受一条消息并返回 SSE 长连接。
     *
     * @param auth 当前认证身份
     * @param sessionId 目标会话
     * @param request 当前消息内容
     * @return 承载 start、delta、done 和 error 事件的响应
     * @throws ApiException 会话不属于当前用户、内容非法或会话正在生成
     */
    @PostMapping(value = "/chat/sessions/{sessionId}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(
            Authentication auth,
            @PathVariable UUID sessionId,
            @RequestBody SendMessageRequest request) {
        UUID user = owner(auth);
        ChatService.Run run = chat.reserve(
                user, sessionId, request == null ? null : request.content());
        // 此处连接超时固定为 125 秒，独立于 ChatService 中可配置的生成超时。
        SseEmitter emitter = new SseEmitter(125000L);
        emitter.onTimeout(() -> chat.disconnect(run));
        emitter.onCompletion(() -> chat.disconnect(run));
        emitter.onError(ex -> chat.disconnect(run));
        chat.start(run, (event, payload) -> {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (Exception e) {
                // 将传输失败交回 ChatService，使其释放对应会话的生成槽位。
                throw new IllegalStateException(e);
            }
        }, emitter::complete);
        return emitter;
    }

    /**
     * @param auth 当前认证身份
     * @param sessionId 会话 UUID
     * @param runId 由 start 事件返回的本轮生成 UUID
     * @return 是否成功停止仍在运行的服务端生成流程
     */
    @DeleteMapping("/chat/sessions/{sessionId}/streams/{runId}")
    public Map<String, Object> abort(
            Authentication auth, @PathVariable UUID sessionId, @PathVariable UUID runId) {
        return Map.of("runId", runId, "aborted", chat.abort(owner(auth), sessionId, runId));
    }
}