package com.luoye.service.chat;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 创建绑定会话短期记忆的 LangChain4j Assistant。
 */
@Component
public class AssistantFactory {

    private final StreamingChatLanguageModel model;

    /**
     * 构造器注入流式模型。
     *
     * @param model 已由 API 模块按供应商配置创建的模型
     */
    public AssistantFactory(StreamingChatLanguageModel model) {
        this.model = model;
    }

    /**
     * 为一轮生成创建隔离的 ChatMemory 和 Assistant。
     *
     * @param id 会话 memory ID
     * @param store 持久化窗口存储
     * @param max 窗口大小
     * @return 已绑定记忆的对话接口
     */
    public LuoyeAssistant create(UUID id, PersistentChatMemoryStore store, int max) {
        // 为接口生成运行时代理；方法注解负责把用户文本和模板变量转换为模型请求。
        return AiServices.builder(LuoyeAssistant.class)
                .streamingChatLanguageModel(model)
                // max 限制消息条数而非轮数，系统消息也占窗口；store 承接窗口读取与更新。
                .chatMemory(MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(max)
                        .chatMemoryStore(store)
                        .build())
                .build();
    }
}
