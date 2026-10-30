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

    private static AnsweringProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("rag.answering", AnsweringProperties.class);
    }
}
