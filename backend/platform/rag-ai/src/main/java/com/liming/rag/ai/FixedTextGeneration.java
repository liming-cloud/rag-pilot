package com.liming.rag.ai;

import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 指定服务商和模型的文字生成（#145），不走管理端的路由：评测里的评判模型有自己的地址和模型。温度为 0，失败重试一次。
 * 只做一次性生成；流式和结构化输出用默认实现（不支持）。
 */
final class FixedTextGeneration implements TextGeneration {

    private final ChatClient client;

    FixedTextGeneration(String baseUrl, String apiKey, String model, Duration timeout, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置模型密钥");
        }
        var options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .temperature(0.0)
                .maxTokens(maxTokens)
                .timeout(timeout)
                .maxRetries(1)
                .build();
        this.client = ChatClient.create(OpenAiChatModel.builder().options(options).build());
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        try {
            var text = client.prompt(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage))))
                    .call().content();
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            throw new ModelCallException("模型服务暂时不可用", e);
        }
    }
}
