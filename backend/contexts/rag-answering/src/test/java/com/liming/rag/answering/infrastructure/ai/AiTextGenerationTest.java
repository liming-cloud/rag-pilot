package com.liming.rag.answering.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.TextGeneration;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.answering.application.ModelUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiTextGenerationTest {

    private final List<TextGeneration.Limits> limits = new ArrayList<>();

    private final AiTextGeneration generation = new AiTextGeneration(new TextGeneration() {
        @Override
        public String complete(String systemPrompt, String userMessage) {
            throw new AssertionError("应当带输出限制调用");
        }

        @Override
        public String complete(String systemPrompt, String userMessage, Limits callLimits) {
            limits.add(callLimits);
            return "好的";
        }
    });

    @Test
    @DisplayName("检索规划用回答模型、只限 token；轻量回复用轻量模型、遇换行就停")
    void mapsLimits() {
        generation.plan("规划", "问题", 96);
        generation.reply("寒暄", "谢谢", 48);

        assertThat(limits).containsExactly(new TextGeneration.Limits(96, List.of(), TextGeneration.Tier.DEFAULT),
                new TextGeneration.Limits(48, List.of("\n"), TextGeneration.Tier.LIGHT));
    }

    @Test
    @DisplayName("模型不可用：转成 answering 的异常，错误码仍是 MODEL_UNAVAILABLE，说明不变")
    void translatesModelFailure() {
        var failing = new AiTextGeneration((system, user) -> {
            throw new ModelCallException("回答模型暂时不可用");
        });

        assertThatThrownBy(() -> failing.stream("s", "u", delta -> {
        }, new CompletableFuture<Void>())).isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
            assertThat(e.code()).isEqualTo("MODEL_UNAVAILABLE");
            assertThat(e.getMessage()).isEqualTo("回答模型暂时不可用");
        });
        assertThatThrownBy(() -> failing.plan("s", "u", 96)).isInstanceOf(ModelUnavailableException.class);
        assertThatThrownBy(() -> failing.reply("s", "u", 48)).isInstanceOf(ModelUnavailableException.class);
    }
}
