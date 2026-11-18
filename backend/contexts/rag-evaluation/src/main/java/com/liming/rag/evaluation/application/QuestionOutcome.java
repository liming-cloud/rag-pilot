package com.liming.rag.evaluation.application;

import java.util.List;

/**
 * 一道题跑出来的结果和三个信号。
 *
 * @param recalledDocumentIds 重排后前几条资料所在的文档
 * @param citedDocuments 回答引用的文档名，和 {@code citedDocumentIds} 一一对应
 */
public record QuestionOutcome(boolean passed, boolean refused, List<Long> recalledDocumentIds,
        List<Long> citedDocumentIds, List<String> citedDocuments, String answerText) {

    public QuestionOutcome {
        recalledDocumentIds = List.copyOf(recalledDocumentIds);
        citedDocumentIds = List.copyOf(citedDocumentIds);
        citedDocuments = List.copyOf(citedDocuments);
    }
}
