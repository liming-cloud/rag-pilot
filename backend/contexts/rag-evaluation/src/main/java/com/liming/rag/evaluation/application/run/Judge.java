package com.liming.rag.evaluation.application.run;

import com.liming.rag.answering.api.ProbeAnswer;
import com.liming.rag.answering.api.ProbeAnswer.ProbePassage;
import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.ExpectedDocument;
import com.liming.rag.evaluation.application.GoldenQuestion;
import com.liming.rag.evaluation.application.MatchMode;
import com.liming.rag.evaluation.application.QuestionOutcome;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 判定一道题（D-069，#224 人定）：不用评判模型，只看系统自己能确定的三个信号。应当拒答的题：拒答即通过；
 * 期望文档的题：没拒答、召回满足、引用满足才通过（多份文档时按题设的全部命中或任一命中）。
 */
final class Judge {

    private Judge() {
    }

    static QuestionOutcome judge(GoldenQuestion question, ProbeAnswer answer) {
        var recalled = answer.passages().stream().map(ProbePassage::documentId).distinct().toList();
        var cited = new LinkedHashMap<Long, String>();
        answer.passages().stream().filter(ProbePassage::cited)
                .forEach(passage -> cited.putIfAbsent(passage.documentId(), passage.documentName()));
        var citedIds = List.copyOf(cited.keySet());
        return new QuestionOutcome(passed(question, answer.refused(), recalled, citedIds), answer.refused(), recalled,
                citedIds, List.copyOf(cited.values()), answer.text());
    }

    static boolean passed(GoldenQuestion question, boolean refused, Collection<Long> recalled, Collection<Long> cited) {
        if (question.expectation() == Expectation.REFUSAL) {
            return refused;
        }
        var expected = question.expected().stream().map(ExpectedDocument::documentId).toList();
        return !refused && hits(question.match(), expected, recalled) && hits(question.match(), expected, cited);
    }

    static boolean hits(MatchMode match, List<Long> expected, Collection<Long> found) {
        return match == MatchMode.ALL ? found.containsAll(expected) : expected.stream().anyMatch(found::contains);
    }
}
