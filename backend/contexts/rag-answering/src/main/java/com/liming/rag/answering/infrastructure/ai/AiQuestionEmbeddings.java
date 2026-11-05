package com.liming.rag.answering.infrastructure.ai;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.ModelCallException;
import com.liming.rag.answering.application.ModelUnavailableException;
import com.liming.rag.answering.application.insight.QuestionEmbeddings;
import java.util.List;
import org.springframework.stereotype.Component;

/** 用 rag-ai 的 {@link EmbeddingClient} 实现问题向量化端口（D-054）。 */
@Component
class AiQuestionEmbeddings implements QuestionEmbeddings {

    private final EmbeddingClient embeddings;

    AiQuestionEmbeddings(EmbeddingClient embeddings) {
        this.embeddings = embeddings;
    }

    @Override
    public List<float[]> embed(List<String> questions) {
        try {
            return embeddings.embed(questions);
        } catch (ModelCallException e) {
            throw new ModelUnavailableException(e.getMessage(), e);
        }
    }

    /** 经 rag-ai 的问题向量缓存（D-053）。 */
    @Override
    public float[] embed(String question) {
        try {
            return embeddings.embedQuestion(question);
        } catch (ModelCallException e) {
            throw new ModelUnavailableException(e.getMessage(), e);
        }
    }
}
