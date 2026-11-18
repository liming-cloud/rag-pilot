package com.liming.rag.evaluation.application.trial;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 试问的结果：回答、引用的文档和召回的原文（重排后的前几条，不论是否达到门槛）。
 *
 * @param refusalReason 拒答原因，没拒答时为 null
 */
public record TrialResult(String text, boolean refused, @Nullable String refusalReason,
        List<CitedDocument> citedDocuments, List<TrialPassage> passages) {

    public record CitedDocument(long documentId, String documentName) {
    }

    /** @param relevance 重排分数；@param cited 回答引用了这一段 */
    public record TrialPassage(long documentId, String documentName, String headingPath, String excerpt,
            double relevance, boolean cited) {
    }
}
