package com.liming.rag.answering.application.ask;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 重排步骤：用重排模型给候选打分，按分数从高到低排列，分数写进 {@link Evidence#score()}。
 * 只排序不过滤：相关度门槛和证据条数是拒答规则的一部分，由 {@link AnswerService} 判断。
 */
final class EvidenceReranker {

    private final RelevanceScoring rerank;

    EvidenceReranker(RelevanceScoring rerank) {
        this.rerank = rerank;
    }

    List<Evidence> rerank(String query, List<Evidence> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 重排时带上章节位置，和入库时向量化的文本一致。
        var scores = rerank.score(query, candidates.stream()
                .map(evidence -> evidence.headingPath() + "\n" + evidence.text())
                .toList());
        return IntStream.range(0, candidates.size())
                .mapToObj(i -> candidates.get(i).withScore(scores.get(i)))
                .sorted(Comparator.comparingDouble(Evidence::score).reversed())
                .toList();
    }
}
