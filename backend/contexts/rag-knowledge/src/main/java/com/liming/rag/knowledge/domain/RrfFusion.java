package com.liming.rag.knowledge.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒数排名融合（Reciprocal Rank Fusion）：每路召回里排第 r 名的结果得 1/(k + r) 分，各路相加。
 *
 * <p>只看名次不看原始分数，向量相似度和 BM25 分数量纲不同也能合并。k 取 60，是文献里的常用值，
 * 后续用评测集调。同分时按首次出现的顺序排，保证结果确定。
 */
public final class RrfFusion {

    public static final int K = 60;

    private RrfFusion() {
    }

    public record Scored(long id, double score) {
    }

    public static List<Scored> fuse(List<List<Long>> rankings, int limit) {
        var scores = new LinkedHashMap<Long, Double>();
        for (var ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                scores.merge(ranking.get(rank), 1.0 / (K + rank + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> new Scored(entry.getKey(), entry.getValue()))
                .toList();
    }
}
