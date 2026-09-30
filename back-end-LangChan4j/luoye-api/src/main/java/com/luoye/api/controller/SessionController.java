package com.luoye.api.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.luoye.api.dto.CreateSessionDTO;
import com.luoye.api.dto.SendMessageDTO;
import com.luoye.api.vo.MessageVO;
import com.luoye.api.vo.SessionVO;
import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.Result;
import com.luoye.common.response.ResultCode;
import com.luoye.dao.entity.Message;
import com.luoye.dao.entity.Session;
import com.luoye.service.chat.ChatRun;
import com.luoye.service.chat.ChatService;
import com.luoye.service.session.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会话与 SSE 接口。
 *
 * <p>普通接口返回统一 {@link Result}；流式对话返回 {@link SseEmitter}，
 * 承载 start、delta、done 和 error 事件。
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

    private final SessionService sessionService;
    private final ChatService chat;

    public SessionController(SessionService sessionService, ChatService chat) {
        this.sessionService = sessionService;
        this.chat = chat;
    }

    /** 从认证上下文提取用户 UUID；JwtAuthFilter 将用户 UUID 写为 principal。 */
    private UUID owner(Authentication auth) {
        try {
            return UUID.fromString(auth.getName());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "无效的认证信息");
        }
    }

    /** @return 当前用户的会话列表 */
    @GetMapping("/sessions")
    public Result<List<SessionVO>> sessions(Authentication auth) {
        List<SessionVO> list = sessionService.listSessions(owner(auth)).stream()
                .map(this::toSessionVO).toList();
        return Result.ok(list);
    }

    /** 创建会话并立即持久化。 */
    @PostMapping("/sessions")
    public Result<SessionVO> create(
            Authentication auth, @RequestBody(required = false) CreateSessionDTO dto) {
        // required=false 允许没有请求体，此时保存空标题。
        Session session = sessionService.createSession(
                owner(auth), dto == null ? null : dto.getTitle());
        return Result.ok(toSessionVO(session));
    }

    /** @return 按 seq 正序排列的历史消息 */
    @GetMapping("/sessions/{id}/messages")
    public Result<List<MessageVO>> messages(Authentication auth, @PathVariable UUID id) {
        List<MessageVO> list = sessionService.history(owner(auth), id).stream()
                .map(this::toMessageVO).toList();
        return Result.ok(list);
    }

    /**
     * 接受一条消息并返回 SSE 长连接。
     *
     * @param sessionId 目标会话
     * @param dto 当前消息内容
     * @return 承载 start、delta、done 和 error 事件的响应
     */
    @PostMapping(value = "/chat/sessions/{sessionId}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(
            Authentication auth,
            @PathVariable UUID sessionId,
            @Valid @RequestBody SendMessageDTO dto) {
        UUID user = owner(auth);
        // 建立流之前先校验内容、归属和会话占位，失败由全局异常处理返回普通错误响应。
        ChatRun run = chat.reserve(user, sessionId, dto.getContent());
        // 连接超时固定 125 秒，独立于 ChatService 中可配置的生成超时。
        SseEmitter emitter = new SseEmitter(125000L);
        // 三类连接结束通知都释放相同 Run；服务层用 ended 标记保证重复通知可安全处理。
        emitter.onTimeout(() -> chat.disconnect(run));
        emitter.onCompletion(() -> chat.disconnect(run));
        emitter.onError(ex -> chat.disconnect(run));
        // 将服务事件转换为 SSE 的 event/data 帧；payload 由 Spring 序列化成 JSON。
        chat.start(run, (event, payload) -> {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (Exception e) {
                // 传输失败交回 ChatService，释放会话生成槽位。
                throw new IllegalStateException(e);
            }
        }, emitter::complete);
        // 返回异步响应通道，文本在之后的模型回调中继续发送。
        return emitter;
    }

    /** @return 是否成功停止仍在运行的服务端生成流程 */
    @DeleteMapping("/chat/sessions/{sessionId}/streams/{runId}")
    public Result<Map<String, Object>> abort(
            Authentication auth, @PathVariable UUID sessionId, @PathVariable UUID runId) {
        boolean aborted = chat.abort(owner(auth), sessionId, runId);
        return Result.ok(Map.of("runId", runId, "aborted", aborted));
    }

    /** 会话实体转视图对象，entity 不直接外露。 */
    private SessionVO toSessionVO(Session s) {
        return new SessionVO(s.getId(), s.getTitle(), s.getCreatedAt(), s.getUpdatedAt());
    }

    /** 消息实体转视图对象。 */
    private MessageVO toMessageVO(Message m) {
        return new MessageVO(m.getId(), m.getSessionId(), m.getSeq(), m.getRole(),
                m.getContent(), m.getStatus(), m.getRunId(), m.getCreatedAt());
    }
}
