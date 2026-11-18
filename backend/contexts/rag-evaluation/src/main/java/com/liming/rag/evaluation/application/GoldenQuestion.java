package com.liming.rag.evaluation.application;

import java.time.OffsetDateTime;
import java.util.List;

/** 一道标准题：在一个知识库里检索；期望命中这个库里的文档，或者期望拒答（这时 {@code expected} 为空）。 */
public record GoldenQuestion(long id, long setId, String question, long knowledgeBaseId, Expectation expectation,
        MatchMode match, List<ExpectedDocument> expected, OffsetDateTime createdAt) {

    public GoldenQuestion {
        expected = List.copyOf(expected);
    }
}
