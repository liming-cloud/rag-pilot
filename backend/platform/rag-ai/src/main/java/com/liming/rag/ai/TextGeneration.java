package com.liming.rag.ai;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 文字生成能力（Spring AI 防腐层，#145）：一次性生成、流式生成（可停止、带备用请求）、结构化输出。上下文只依赖这个接口，
 * 不接触 Spring AI 的类型；实现内部用 Spring AI 的 {@code ChatClient}，并留下框架自带的观测（耗时、token 用量）。
 * 提示词是调用方的策略，这里只负责怎么调用模型。
 */
public interface TextGeneration {

    String complete(String systemPrompt, String userMessage);

    /** 用哪档模型：回答相关的调用用回答模型；会话标题这类轻量任务用单独配置的便宜模型（D-036）。 */
    enum Tier {
        DEFAULT,
        LIGHT
    }

    /**
     * 单次调用的输出限制：最多生成多少 token、遇到哪些字符串就停。用于只要一小段输出的调用（如改写问题），
     * 不必等模型把长输出生成完。
     */
    record Limits(int maxTokens, List<String> stop, Tier tier) {

        public Limits {
            stop = List.copyOf(stop);
            tier = tier == null ? Tier.DEFAULT : tier;
        }

        public Limits(int maxTokens, List<String> stop) {
            this(maxTokens, stop, Tier.DEFAULT);
        }
    }

    /** 带输出限制的生成；默认忽略限制，供不支持的本地实现使用。 */
    default String complete(String systemPrompt, String userMessage, Limits limits) {
        return complete(systemPrompt, userMessage);
    }

    /**
     * 结构化输出：要求模型按 {@code type}（record）的字段输出 JSON，解析后返回；按 {@code limits} 的用途选模型。
     * 本地确定性实现不支持，抛 {@link ModelCallException}，调用方应退回不依赖模型的做法。
     *
     * @throws ModelCallException 模型不可用，或输出不是约定的 JSON
     */
    default <T> T structured(String systemPrompt, String userMessage, Class<T> type, Limits limits) {
        throw new ModelCallException("本地模型实现不支持结构化输出");
    }

    /**
     * 指定服务商和模型、不走管理端路由的文字生成（#145），只支持一次性生成。评测里的评判模型用：温度 0，失败重试一次。
     *
     * @param apiKey 只从环境变量读取，不进日志
     * @throws IllegalStateException 没有密钥
     */
    static TextGeneration fixed(String baseUrl, String apiKey, String model, Duration timeout, int maxTokens) {
        return new FixedTextGeneration(baseUrl, apiKey, model, timeout, maxTokens);
    }

    /**
     * 流式生成：每收到一段文字就交给 {@code onDelta}，结束后返回完整回复。
     * 默认实现一次性生成后整段交出，供不支持流式的本地实现使用。
     */
    default String stream(String systemPrompt, String userMessage, Consumer<String> onDelta) {
        var text = complete(systemPrompt, userMessage);
        if (!text.isEmpty()) {
            onDelta.accept(text);
        }
        return text;
    }

    /**
     * 可以中途停止的流式生成：{@code stop} 完成后不再等后续文字，返回已生成的部分（D-036 停止生成）。
     * 默认实现开始前检查一次，供不支持流式的本地实现使用。
     */
    default String stream(String systemPrompt, String userMessage, Consumer<String> onDelta, CompletionStage<?> stop) {
        if (stop.toCompletableFuture().isDone()) {
            return "";
        }
        return stream(systemPrompt, userMessage, onDelta);
    }
}
