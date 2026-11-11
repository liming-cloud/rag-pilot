package com.liming.rag.knowledge.infrastructure.ai;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.knowledge.api.EmbeddingUnavailableException;
import com.liming.rag.knowledge.application.TextEmbedding;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 用 rag-ai 的 {@link EmbeddingClient} 实现向量化端口（D-054），把 {@link ModelCallException} 转成本上下文的异常。 */
@Component
class AiTextEmbedding implements TextEmbedding {

    private final EmbeddingClient embeddings;

    AiTextEmbedding(EmbeddingClient embeddings) {
        this.embeddings = embeddings;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return call(() -> embeddings.embed(texts));
    }

    /** 经 rag-ai 的问题向量缓存（D-053）。 */
    @Override
    public float[] embedQuestion(String question) {
        return call(() -> embeddings.embedQuestion(question));
    }

    private static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (ModelCallException e) {
            throw new EmbeddingUnavailableException(e.getMessage(), e);
        }
    }
}
