package com.liming.rag.answering.infrastructure.ai;

import com.liming.rag.ai.ModelCallException;
import com.liming.rag.ai.TextGeneration;
import com.liming.rag.answering.application.ModelUnavailableException;
import com.liming.rag.answering.application.ask.AnswerGeneration;
import com.liming.rag.answering.application.conversation.FollowUpPlanning;
import com.liming.rag.answering.application.conversation.LightReplies;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** 用 rag-ai 的 {@link TextGeneration} 实现回答生成、检索规划和轻量回复（D-054），把 {@link ModelCallException} 转成本上下文的异常。 */
@Component
class AiTextGeneration implements AnswerGeneration, FollowUpPlanning, LightReplies {

    private static final List<String> ONE_LINE = List.of("\n");

    private final TextGeneration generation;

    AiTextGeneration(TextGeneration generation) {
        this.generation = generation;
    }

    @Override
    public String stream(String systemPrompt, String userMessage, Consumer<String> onDelta, CompletionStage<?> stop) {
        try {
            return generation.stream(systemPrompt, userMessage, onDelta, stop);
        } catch (ModelCallException e) {
            throw new ModelUnavailableException(e.getMessage(), e);
        }
    }

    @Override
    public String plan(String systemPrompt, String userMessage, int maxTokens) {
        return complete(systemPrompt, userMessage, new TextGeneration.Limits(maxTokens, List.of()));
    }

    @Override
    public String reply(String systemPrompt, String userMessage, int maxTokens) {
        return complete(systemPrompt, userMessage,
                new TextGeneration.Limits(maxTokens, ONE_LINE, TextGeneration.Tier.LIGHT));
    }

    private String complete(String systemPrompt, String userMessage, TextGeneration.Limits limits) {
        try {
            return generation.complete(systemPrompt, userMessage, limits);
        } catch (ModelCallException e) {
            throw new ModelUnavailableException(e.getMessage(), e);
        }
    }
}
