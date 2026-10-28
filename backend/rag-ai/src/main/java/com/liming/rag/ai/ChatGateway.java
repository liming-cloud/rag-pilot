package com.liming.rag.ai;

import java.util.List;
import java.util.function.Consumer;

/** 对话模型：给定系统提示和用户消息，返回完整回复文本。 */
public interface ChatGateway {

    String complete(String systemPrompt, String userMessage);

    /**
     * 单次调用的输出限制：最多生成多少 token、遇到哪些字符串就停。用于只要一小段输出的调用（如改写问题），
     * 不必等模型把长输出生成完。
     */
    record Limits(int maxTokens, List<String> stop) {

        public Limits {
            stop = List.copyOf(stop);
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
}
