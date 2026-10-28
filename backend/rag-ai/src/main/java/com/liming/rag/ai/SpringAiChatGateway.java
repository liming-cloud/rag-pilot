package com.liming.rag.ai;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 通过 Spring AI 调用 OpenAI 兼容的对话接口。不重试，失败直接告诉用户稍后再问。 */
final class SpringAiChatGateway implements ChatGateway {

    private final ChatModel model;
    private final OpenAiChatOptions options;

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
        this.options = options;
        this.model = OpenAiChatModel.builder().options(options).build();
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        try {
            var response = model.call(prompt(systemPrompt, userMessage));
            var text = response.getResult().getOutput().getText();
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    /**
     * Spring AI 2.0 不会把单次调用的选项和默认选项合并（调用带了选项就只用它），所以从默认选项复制一份再改限制。
     */
    @Override
    public String complete(String systemPrompt, String userMessage, Limits limits) {
        var limited = options.mutate().maxTokens(limits.maxTokens()).stop(limits.stop()).build();
        try {
            var response = model.call(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)),
                    limited));
            var text = response.getResult().getOutput().getText();
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    /** 在调用线程上阻塞到生成结束；调用方在虚拟线程里运行，不占用平台线程。 */
    @Override
    public String stream(String systemPrompt, String userMessage, Consumer<String> onDelta) {
        var text = new StringBuilder();
        try {
            model.stream(prompt(systemPrompt, userMessage))
                    .mapNotNull(response -> response.getResult() == null ? null : response.getResult().getOutput().getText())
                    .filter(delta -> !delta.isEmpty())
                    .doOnNext(delta -> {
                        text.append(delta);
                        onDelta.accept(delta);
                    })
                    .blockLast();
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        return text.toString();
    }

    private static Prompt prompt(String systemPrompt, String userMessage) {
        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)));
    }

    private static ModelCallException unavailable(RuntimeException e) {
        return new ModelCallException("生成回答失败：对话模型服务暂时不可用，请稍后重试", e);
    }
}
