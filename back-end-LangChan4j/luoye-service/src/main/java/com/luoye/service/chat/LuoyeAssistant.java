package com.luoye.service.chat;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.UUID;

/**
 * 落叶对话编排接口。
 *
 * <p>由 LangChain4j 在运行时生成实现。{@code sessionId} 绑定短期记忆窗口，
 * {@code TokenStream} 将模型输出逐段交给 SSE 层。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
public interface LuoyeAssistant {

    /**
     * 流式生成一轮助手回复。
     *
     * @param sessionId 会话 ID，同时作为 LangChain4j 的 memory ID
     * @param userMessage 当前用户消息
     * @param persona 当前人格提示词
     * @param memoryContext 长期记忆上下文占位；M1 暂未启用
     * @param kbContext 知识库上下文占位；M1 暂未启用
     * @return LangChain4j 流式令牌管道
     */
    @SystemMessage("{{persona}}\n当前仅使用本会话短期记忆。不要声称拥有长期记忆、联网或工具能力。\n记忆摘要：{{memoryContext}}\n知识库上下文：{{kbContext}}")
    TokenStream stream(
            @MemoryId UUID sessionId,
            @UserMessage String userMessage,
            @V("persona") String persona,
            @V("memoryContext") String memoryContext,
            @V("kbContext") String kbContext);
}