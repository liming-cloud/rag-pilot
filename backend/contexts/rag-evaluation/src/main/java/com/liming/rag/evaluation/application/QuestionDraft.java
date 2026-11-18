package com.liming.rag.evaluation.application;

import java.util.List;

/** 要保存的标准题，已校验过。期望拒答时 {@code expected} 为空。 */
public record QuestionDraft(String question, long knowledgeBaseId, Expectation expectation, MatchMode match,
        List<ExpectedDocument> expected) {

    public QuestionDraft {
        expected = List.copyOf(expected);
    }
}
