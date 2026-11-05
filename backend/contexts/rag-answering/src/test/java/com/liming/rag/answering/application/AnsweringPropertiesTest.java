package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AnsweringPropertiesTest {

    @Test
    @DisplayName("没有配置时用默认值")
    void defaults() {
        var properties = bind(Map.of());

        assertThat(properties.candidates()).isEqualTo(30);
        assertThat(properties.evidenceLimit()).isEqualTo(8);
        assertThat(properties.minRelevance()).isEqualTo(0.2);
        assertThat(properties.rewriteOnLowRelevance()).isTrue();
        assertThat(properties.rewriteMinRelevance()).isEqualTo(0.03);
        assertThat(properties.rewriteTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("按 rag.answering 绑定")
    void binds() {
        var properties = bind(Map.of("rag.answering.min-relevance", "0.35", "rag.answering.rewrite-timeout", "2s"));

        assertThat(properties.minRelevance()).isEqualTo(0.35);
        assertThat(properties.rewriteTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("配置非法时绑定失败，应用启动不起来，而不是带着错误的门槛运行")
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> bind(Map.of("rag.answering.min-relevance", "1.5"))).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("min-relevance");
        assertThatThrownBy(() -> bind(Map.of("rag.answering.evidence-limit", "0"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("rag.answering.candidates", "3", "rag.answering.evidence-limit", "8")))
                .as("证据数不能多于候选数").isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("rag.answering.rewrite-timeout", "0s"))).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("会话配置（D-036）：保留 30 天，检索规划看 6 轮、生成带 3 轮，规划 3 秒超时")
    void conversationDefaults() {
        var conversation = bind(Map.of()).conversation();

        assertThat(conversation.retention()).isEqualTo(Duration.ofDays(30));
        assertThat(conversation.planningTurns()).isEqualTo(6);
        assertThat(conversation.generationTurns()).isEqualTo(3);
        assertThat(conversation.historyMaxChars()).isEqualTo(3000);
        assertThat(conversation.planningTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(bind(Map.of("rag.answering.conversation.retention", "7d")).conversation().retention())
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("会话配置非法时启动失败")
    void rejectsInvalidConversationValues() {
        assertThatThrownBy(() -> bind(Map.of("rag.answering.conversation.retention", "0d")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("rag.answering.conversation.generation-turns", "7")))
                .as("生成带的轮数不多于检索规划看的轮数").isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("rag.answering.conversation.history-max-chars", "100")))
                .isInstanceOf(BindException.class);
    }

    private static AnsweringProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("rag.answering", AnsweringProperties.class);
    }
}
