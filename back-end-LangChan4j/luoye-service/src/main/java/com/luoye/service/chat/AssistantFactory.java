package com.luoye.service.chat;

import com.luoye.service.tool.LuoyeTools;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 创建绑定短期记忆与工具的 Assistant。
 */
@Component
public class AssistantFactory {

    private final StreamingChatLanguageModel model;
    private final LuoyeTools tools;

    public AssistantFactory(StreamingChatLanguageModel model, LuoyeTools tools) {
        this.model = model;
        this.tools = tools;
    }

    public LuoyeAssistant create(UUID id, PersistentChatMemoryStore store, int max) {
        return AiServices.builder(LuoyeAssistant.class)
                .streamingChatLanguageModel(model)
                .chatMemory(MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(max)
                        .chatMemoryStore(store)
                        .build())
                .tools(tools)
                .build();
    }
}
