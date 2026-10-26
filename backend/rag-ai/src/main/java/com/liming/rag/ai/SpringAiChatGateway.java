package com.liming.rag.ai;

import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 通过 Spring AI 调用 OpenAI 兼容的对话接口。不重试，失败直接告诉用户稍后再问。 */
final class SpringAiChatGateway implements ChatGateway {

    private final ChatModel model;

    SpringAiChatGateway(AiProperties properties) {
        var options = OpenAiChatOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.requireApiKey())
                .model(properties.chat().model())
                .temperature(properties.chat().temperature())
                .maxTokens(properties.chat().maxTokens())
                .timeout(properties.timeout())
                .maxRetries(0)
                .build();
        this.model = OpenAiChatModel.builder().options(options).build();
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        try {
            var response = model.call(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage))));
            var text = response.getResult().getOutput().getText();
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            throw new ModelCallException("生成回答失败：对话模型服务暂时不可用，请稍后重试", e);
        }
    }
}
