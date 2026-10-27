package com.liming.rag.answering.application;

import com.liming.rag.ai.RerankClient;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

/**
 * 重排步骤：用重排模型给候选打分，按分数从高到低排列，分数写进 {@link Document#getScore()}。
 * 只排序不过滤：相关度门槛和证据条数是拒答规则的一部分，由 {@link AnswerService} 判断。
 */
final class RerankPostProcessor implements DocumentPostProcessor {

    private final RerankClient rerank;

    RerankPostProcessor(RerankClient rerank) {
        this.rerank = rerank;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        // 重排时带上章节位置，和入库时向量化的文本一致。
        var scores = rerank.score(query.text(), documents.stream()
                .map(document -> Evidence.headingPath(document) + "\n" + document.getText())
                .toList());
        return IntStream.range(0, documents.size())
                .mapToObj(i -> documents.get(i).mutate().score(scores.get(i)).build())
                .sorted(Comparator.comparingDouble(Document::getScore).reversed())
                .toList();
    }
}
