package com.luoye.service.support;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 当前对话轮次的用户/会话上下文，供 @Tool 读取。 */
public final class RequestContext {

    private static final ConcurrentMap<UUID, UUID> SESSION_USERS = new ConcurrentHashMap<>();

    private RequestContext() {
    }

    public static void bind(UUID sessionId, UUID userId) {
        SESSION_USERS.put(sessionId, userId);
    }

    public static void unbind(UUID sessionId) {
        if (sessionId != null) {
            SESSION_USERS.remove(sessionId);
        }
    }

    public static UUID userId() {
        if (SESSION_USERS.size() == 1) {
            return SESSION_USERS.values().iterator().next();
        }
        UUID first = SESSION_USERS.values().stream().findFirst().orElse(null);
        if (first == null) {
            throw new IllegalStateException("工具调用缺少用户上下文");
        }
        return first;
    }

    public static UUID sessionId() {
        return SESSION_USERS.size() == 1
                ? SESSION_USERS.keySet().iterator().next()
                : SESSION_USERS.keySet().stream().findFirst().orElse(null);
    }

    public static void set(UUID userId, UUID sessionId) {
        bind(sessionId, userId);
    }

    public static void clear() {
        /* 由 unbind(session) 精确释放，避免并行会话互相清掉。 */
    }
}
