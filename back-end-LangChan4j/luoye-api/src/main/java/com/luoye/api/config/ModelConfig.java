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
        // 从 application.yml 与环境变量合并后的 Spring Environment 中读取对话模型配置。
        String provider = env.getProperty("langchain4j.chat.provider", "openai-compatible");
        String model = env.getProperty("langchain4j.chat.model", "");
        // 模型名称没有可用默认值，缺失时在启动阶段报错，避免首次对话才发现配置问题。
        if (model.isBlank()) {
            throw new IllegalStateException("Please configure LLM_CHAT_MODEL");
        }
        // timeout 是模型客户端请求超时；temperature 控制采样随机性，二者独立于 SSE 总超时。
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
        // Ollama 分支已返回；其余只接受 OpenAI 兼容协议，未知名称不静默切换供应商。
        if (!provider.equals("openai-compatible")) {
            throw new IllegalStateException("Unsupported LLM_CHAT_PROVIDER");
        }
        String key = env.getProperty("langchain4j.chat.api-key", "");
        if (key.isBlank()) {
            throw new IllegalStateException("Please configure LLM_CHAT_API_KEY");
        }
        // 自定义 base-url 可接兼容服务，未设置则使用 OpenAI；关闭请求/响应日志以免输出对话与密钥。
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
        // 核心线程 2、最多 4，额外空闲线程 30 秒后回收；先排队，队列满后再扩容到上限。
        // 队列容量 16；线程和队列均满时 AbortPolicy 抛出拒绝异常，由 ChatService 转成繁忙事件。
        return new ThreadPoolExecutor(
                2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
    }

    /** @return 为生成任务安排截止时间的单线程调度器 */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService chatScheduler() {
        // 超时检查共用单个调度线程；容器关闭时由 destroyMethod 调用 shutdownNow。
        return Executors.newSingleThreadScheduledExecutor();
    }
}