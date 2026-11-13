package com.liming.rag.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.cache.Caches;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CachedQuestionEmbeddingsTest {

    private final List<List<String>> calls = new ArrayList<>();
    private final EmbeddingClient model = texts -> {
        calls.add(texts);
        return new HashingEmbeddingClient().embed(texts);
    };
    private final Caches caches = Caches.inMemory();

    @Test
    void sameQuestionCallsModelOnce() {
        var embeddings = new CachedQuestionEmbeddings(model, "BAAI/bge-m3", caches);

        var first = embeddings.embedQuestion("鲜食怎么下架");
        var second = embeddings.embedQuestion("鲜食怎么下架");

        assertThat(second).isEqualTo(first).isEqualTo(HashingEmbeddingClient.vectorOf("鲜食怎么下架"));
        assertThat(calls).containsExactly(List.of("鲜食怎么下架"));
    }

    @Test
    void differentModelDoesNotReuseVectors() {
        new CachedQuestionEmbeddings(model, "BAAI/bge-m3", caches).embedQuestion("鲜食怎么下架");
        new CachedQuestionEmbeddings(model, "other-model", caches).embedQuestion("鲜食怎么下架");

        assertThat(calls).hasSize(2);
    }

    @Test
    void batchEmbeddingIsNotCached() {
        var embeddings = new CachedQuestionEmbeddings(model, "BAAI/bge-m3", caches);

        embeddings.embed(List.of("a", "b"));
        embeddings.embed(List.of("a", "b"));

        assertThat(calls).hasSize(2);
    }

    @Test
    void ingestionKeepsRateLimitBackoffOfDelegate() {
        var ingestion = new ArrayList<List<String>>();
        var delegate = new EmbeddingClient() {
            @Override
            public List<float[]> embed(List<String> texts) {
                throw new AssertionError("入库要走带退避的 embedForIngestion（#226）");
            }

            @Override
            public List<float[]> embedForIngestion(List<String> texts) {
                ingestion.add(texts);
                return new HashingEmbeddingClient().embed(texts);
            }
        };

        new CachedQuestionEmbeddings(delegate, "BAAI/bge-m3", caches).embedForIngestion(List.of("a"));

        assertThat(ingestion).containsExactly(List.of("a"));
    }
}
