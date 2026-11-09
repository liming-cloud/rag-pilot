package com.liming.rag.ai;

import com.liming.rag.cache.typed.CacheSpec;
import com.liming.rag.cache.Caches;
import com.liming.rag.cache.typed.TypedCache;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 问题向量缓存（D-053）：同一个问题检索时、回答记下后各算一次向量，缓存后只调一次模型。键是模型名和问题的 SHA-256，
 * 换了嵌入模型自然不会读到旧向量，Redis 里也没有问题原文。批量向量化（入库、补算）不经缓存。
 */
final class CachedQuestionEmbeddings implements EmbeddingClient {

    record QuestionVector(float[] vector) {
    }

    static final CacheSpec<QuestionVector> VECTORS = new CacheSpec<>("question-vectors", QuestionVector.class);

    private final EmbeddingClient delegate;
    private final String model;
    private final TypedCache<QuestionVector> cache;

    CachedQuestionEmbeddings(EmbeddingClient delegate, String model, Caches caches) {
        this.delegate = delegate;
        this.model = model;
        this.cache = caches.cache(VECTORS);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return delegate.embed(texts);
    }

    @Override
    public float[] embedQuestion(String question) {
        var cached = cache.get(key(question), () -> new QuestionVector(delegate.embedQuestion(question)));
        return cached == null ? delegate.embedQuestion(question) : cached.vector();
    }

    private String key(String question) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((model + "\n" + question).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
