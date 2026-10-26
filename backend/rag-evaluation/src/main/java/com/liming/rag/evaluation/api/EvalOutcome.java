package com.liming.rag.evaluation.api;

import java.util.List;

/**
 * 一道题在检索和问答两步的实际结果。
 *
 * @param retrieved 检索前 10 条
 * @param citedDocuments 回答引用的文档名，按引用序号排列
 * @param topRelevance 重排最高分，没有检索结果时为 null
 */
public record EvalOutcome(EvalCase evalCase, List<Retrieved> retrieved, boolean refused, String refusalReason,
        String answer, List<String> citedDocuments, Double topRelevance, long searchMillis, long askMillis) {

    public record Retrieved(String document, String content) {
    }

    public EvalOutcome {
        retrieved = List.copyOf(retrieved);
        citedDocuments = List.copyOf(citedDocuments);
    }
}
