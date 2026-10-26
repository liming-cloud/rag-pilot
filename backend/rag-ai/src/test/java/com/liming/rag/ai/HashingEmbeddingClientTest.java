package com.liming.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HashingEmbeddingClientTest {

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    @Test
    @DisplayName("向量维度固定、已归一化、结果确定")
    void deterministicNormalizedVectors() {
        var first = HashingEmbeddingClient.vectorOf("临期商品下架");
        assertThat(first).hasSize(EmbeddingClient.DIMENSIONS);
        assertThat(cosine(first, first)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(HashingEmbeddingClient.vectorOf("临期商品下架")).containsExactly(first);
    }

    @Test
    @DisplayName("字面相近的文本更相似")
    void similarTextsAreCloser() {
        var query = HashingEmbeddingClient.vectorOf("青柚气泡水放在哪里");
        var related = HashingEmbeddingClient.vectorOf("青柚气泡水位于一层 A 区货架");
        var unrelated = HashingEmbeddingClient.vectorOf("会员积分可以兑换保温杯");
        assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated));
    }
}
