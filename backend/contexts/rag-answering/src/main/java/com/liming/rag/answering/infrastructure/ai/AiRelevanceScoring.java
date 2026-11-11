package com.liming.rag.answering.infrastructure.ai;

import com.liming.rag.ai.RerankClient;
import com.liming.rag.answering.application.ask.RelevanceScoring;
import java.util.List;
import org.springframework.stereotype.Component;

/** 用 rag-ai 的 {@link RerankClient} 实现重排端口（D-054）。 */
@Component
class AiRelevanceScoring implements RelevanceScoring {

    private final RerankClient rerank;

    AiRelevanceScoring(RerankClient rerank) {
        this.rerank = rerank;
    }

    @Override
    public List<Double> score(String query, List<String> documents) {
        return ModelCalls.call(() -> rerank.score(query, documents));
    }
}
