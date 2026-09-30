package com.luoye.api.config;

import java.time.Duration;
import java.util.concurrent.*;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/**
 * 流式模型与任务执行器配置，依赖 LangChain4j 的供应商适配器。
 *
 * <p>按启动配置选择 OpenAI 兼容接口或 Ollama；此处不实现运行时模型切换。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Configuration
public class ModelConfig {

    /**
     * 根据自定义 langchain4j.chat 配置创建流式模型。
     *
     * @param env Spring 环境配置
     * @return 后续 Assistant 共享的模型客户端
     * @throws IllegalStateException 模型名或必要 API Key 缺失，或供应商不支持
     */
    @Bean
    StreamingChatLanguageModel chatModel(Environment env) {
        String provider = env.getProperty("langchain4j.chat.provider", "openai-compatible");
        String model = env.getProperty("langchain4j.chat.model", "");
        if (model.isBlank()) {
            throw new IllegalStateException("Please configure LLM_CHAT_MODEL");
        }
        Duration timeout = Duration.ofSeconds(
                env.getProperty("langchain4j.chat.timeout-seconds", Long.class, 60L));
        double temperature = env.getProperty("langchain4j.chat.temperature", Double.class, 0.7);
        String base = env.getProperty("langchain4j.chat.base-url", "");
        if (provider.equals("ollama")) {
            // Ollama 默认使用本地地址，不要求云端 API Key。
            return OllamaStreamingChatModel.builder()
                    .baseUrl(base.isBlank() ? env.getProperty("langchain4j.ollama.base-url") : base)
                    .modelName(model)
                    .temperature(temperature)
                    .timeout(timeout)
                    .build();
        }
        if (!provider.equals("openai-compatible")) {
            throw new IllegalStateException("Unsupported LLM_CHAT_PROVIDER");
        }
        String key = env.getProperty("langchain4j.chat.api-key", "");
        if (key.isBlank()) {
            throw new IllegalStateException("Please configure LLM_CHAT_API_KEY");
        }
        return OpenAiStreamingChatModel.builder()
                .baseUrl(base.isBlank() ? "https://api.openai.com/v1" : base)
                .apiKey(key)
                .modelName(model)
                .temperature(temperature)
                .timeout(timeout)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 限制待启动生成任务的队列，队列满时让 ChatService 返回繁忙事件。
     *
     * @return 由 Spring 在关闭时停止的执行器；不等同于供应商在途请求数限制
     */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService chatExecutor() {
        return new ThreadPoolExecutor(
                2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
    }

    /** @return 为生成任务安排截止时间的单线程调度器 */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService chatScheduler() {
        return Executors.newSingleThreadScheduledExecutor();
    }
}