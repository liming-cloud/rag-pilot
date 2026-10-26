package com.liming.rag.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地确定性重排：问题的相邻两字组合有多大比例出现在候选文本里。只用于测试和离线开发。
 */
final class LexicalRerankClient implements RerankClient {

    @Override
    public List<Double> score(String query, List<String> documents) {
        var wanted = bigrams(query);
        return documents.stream()
                .map(document -> {
                    if (wanted.isEmpty()) {
                        return 0.0;
                    }
                    var present = bigrams(document);
                    long hit = wanted.stream().filter(present::contains).count();
                    return (double) hit / wanted.size();
                })
                .toList();
    }

    private static Set<String> bigrams(String text) {
        var compact = text.replaceAll("[\\s\\p{Punct}，。？！、：；“”‘’（）《》]", "");
        var result = new HashSet<String>();
        for (int i = 0; i + 1 < compact.length(); i++) {
            result.add(compact.substring(i, i + 2));
        }
        return result;
    }
}
