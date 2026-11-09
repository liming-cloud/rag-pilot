package com.liming.rag.knowledge.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.knowledge.api.EmbeddingUnavailableException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiTextEmbeddingTest {

    @Test
    @DisplayName("问题向量经 rag-ai 的问题向量入口（带缓存），分块向量经批量入口")
    void delegatesToEmbeddingClient() {
        var questions = new ArrayList<String>();
        var embedding = new AiTextEmbedding(new EmbeddingClient() {
            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> new float[] {1}).toList();
            }

            @Override
            public float[] embedQuestion(String question) {
                questions.add(question);
                return new float[] {2};
            }
        });

        assertThat(embedding.embed(List.of("a", "b"))).hasSize(2);
        assertThat(embedding.embedQuestion("交班")).containsExactly(2f);
        assertThat(questions).containsExactly("交班");
    }

    @Test
    @DisplayName("嵌入模型不可用：转成 knowledge 的异常，错误码仍是 MODEL_UNAVAILABLE，说明不变")
    void translatesModelFailure() {
        var embedding = new AiTextEmbedding(texts -> {
            throw new ModelCallException("向量化失败：嵌入模型服务暂时不可用");
        });

        assertThatThrownBy(() -> embedding.embed(List.of("a")))
                .isInstanceOfSatisfying(EmbeddingUnavailableException.class, e -> {
                    assertThat(e.code()).isEqualTo("MODEL_UNAVAILABLE");
                    assertThat(e.getMessage()).isEqualTo("向量化失败：嵌入模型服务暂时不可用");
                });
        assertThatThrownBy(() -> embedding.embedQuestion("a")).isInstanceOf(EmbeddingUnavailableException.class);
    }
}
