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
    // {{变量名}} 由同名 @V 参数替换；@MemoryId 标识会话，@UserMessage 指定本轮输入。
    @SystemMessage("{{persona}}\n记忆摘要：{{memoryContext}}\n知识库上下文：{{kbContext}}\n没有出现在记忆摘要或知识库中的事实，不要假装记得。引用知识库用 [n]。")
    TokenStream stream(
            @MemoryId UUID sessionId,
            @UserMessage String userMessage,
            @V("persona") String persona,
            @V("memoryContext") String memoryContext,
            @V("kbContext") String kbContext);
}
