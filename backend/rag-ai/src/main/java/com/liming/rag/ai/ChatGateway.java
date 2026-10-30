package com.liming.rag.ai;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** 对话模型：给定系统提示和用户消息，返回完整回复文本。 */
public interface ChatGateway {

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
