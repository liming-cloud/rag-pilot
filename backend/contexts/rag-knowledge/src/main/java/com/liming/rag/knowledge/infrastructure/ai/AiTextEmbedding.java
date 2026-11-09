package com.liming.rag.knowledge.infrastructure.ai;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.knowledge.api.EmbeddingUnavailableException;
import com.liming.rag.knowledge.application.TextEmbedding;
import java.util.List;
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
        try {
            return embeddings.embed(texts);
        } catch (ModelCallException e) {
            throw new EmbeddingUnavailableException(e.getMessage(), e);
        }
    }

    /** 经 rag-ai 的问题向量缓存（D-053）。 */
    @Override
    public float[] embedQuestion(String question) {
        try {
            return embeddings.embedQuestion(question);
        } catch (ModelCallException e) {
            throw new EmbeddingUnavailableException(e.getMessage(), e);
        }
    }
}
