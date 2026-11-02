package com.liming.rag.answering.application;

import com.liming.rag.settings.api.IndustryPack;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 用行业词表扩展检索用的问题（D-045）：问题里出现某组的下位词或同义词、又没提到上位词时，把上位词追加在后面，
 * 员工用口语说具体东西、资料里只写类别时也能检索到。只改检索文本，给模型看的仍是原问题；
 * 权限过滤在检索服务里，与这里无关。
 */
final class QueryExpansion {

    private final List<IndustryPack.Term> vocabulary;

    QueryExpansion(List<IndustryPack.Term> vocabulary) {
        this.vocabulary = List.copyOf(vocabulary);
    }

    String expand(String query) {
        var added = new LinkedHashSet<String>();
        for (var term : vocabulary) {
            if (!query.contains(term.term()) && term.members().stream().anyMatch(query::contains)) {
                added.add(term.term());
            }
        }
        return added.isEmpty() ? query : query + " " + String.join(" ", added);
    }
}
