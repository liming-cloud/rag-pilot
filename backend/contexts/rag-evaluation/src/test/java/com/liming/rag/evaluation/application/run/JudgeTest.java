package com.liming.rag.evaluation.application.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.api.ProbeAnswer;
import com.liming.rag.answering.api.ProbeAnswer.ProbePassage;
import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.ExpectedDocument;
import com.liming.rag.evaluation.application.GoldenQuestion;
import com.liming.rag.evaluation.application.MatchMode;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JudgeTest {

    private static GoldenQuestion expecting(MatchMode match, long... documentIds) {
        var expected = java.util.Arrays.stream(documentIds).mapToObj(id -> new ExpectedDocument(id, "文档" + id)).toList();
        return new GoldenQuestion(1, 1, "问题", 7, Expectation.DOCUMENTS, match, expected, OffsetDateTime.MIN);
    }

    private static GoldenQuestion refusal() {
        return new GoldenQuestion(1, 1, "问题", 7, Expectation.REFUSAL, MatchMode.ALL, List.of(), OffsetDateTime.MIN);
    }

    @Test
    @DisplayName("应当拒答的题：拒答才通过")
    void refusalPassesOnlyWhenRefused() {
        assertThat(Judge.passed(refusal(), true, List.of(), List.of())).isTrue();
        assertThat(Judge.passed(refusal(), false, List.of(3L), List.of(3L))).isFalse();
    }

    @Test
    @DisplayName("期望文档的题：没拒答、召回满足、引用满足才通过")
    void documentsNeedAllThreeSignals() {
        var question = expecting(MatchMode.ALL, 3);
        assertThat(Judge.passed(question, false, List.of(3L, 4L), List.of(3L))).isTrue();
        assertThat(Judge.passed(question, true, List.of(3L), List.of(3L))).as("拒答了").isFalse();
        assertThat(Judge.passed(question, false, List.of(4L), List.of(3L))).as("没召回").isFalse();
        assertThat(Judge.passed(question, false, List.of(3L), List.of(4L))).as("没引用").isFalse();
    }

    @Test
    @DisplayName("多份期望文档：全部命中要每份都召回和引用，任一命中有一份就行")
    void matchModes() {
        assertThat(Judge.passed(expecting(MatchMode.ALL, 3, 5), false, List.of(3L, 5L), List.of(3L))).isFalse();
        assertThat(Judge.passed(expecting(MatchMode.ALL, 3, 5), false, List.of(3L, 5L), List.of(5L, 3L))).isTrue();
        assertThat(Judge.passed(expecting(MatchMode.ANY, 3, 5), false, List.of(5L), List.of(5L))).isTrue();
        assertThat(Judge.passed(expecting(MatchMode.ANY, 3, 5), false, List.of(5L), List.of(6L))).isFalse();
    }

    @Test
    @DisplayName("召回取重排后各段所在的文档，引用取被引用的段，同一文档只算一次")
    void signalsFromPassages() {
        var answer = new ProbeAnswer("回答[1][2]", false, null, List.of(
                new ProbePassage(3, "临期.md", "", "a", 0.9, true),
                new ProbePassage(3, "临期.md", "", "b", 0.8, true),
                new ProbePassage(4, "陈列.md", "", "c", 0.2, false)));

        var outcome = Judge.judge(expecting(MatchMode.ALL, 3), answer);

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.recalledDocumentIds()).containsExactly(3L, 4L);
        assertThat(outcome.citedDocumentIds()).containsExactly(3L);
        assertThat(outcome.citedDocuments()).containsExactly("临期.md");
    }
}
