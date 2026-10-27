package com.liming.rag.evaluation.api;

import java.util.List;

/**
 * 一道题在检索和问答两步的实际结果。
 *
 * @param retrieved 检索前 10 条
 * @param citedDocuments 回答引用的文档名，按引用序号排列
 * @param topRelevance 重排最高分，没有检索结果时为 null
 * @param firstEventMillis 流式问答从发出请求到收到第一个事件的毫秒数，即首字延迟；拒答时第一个事件就是结果
 * @param askMillis 流式问答从发出请求到收到最终结果的毫秒数
 */
public record EvalOutcome(EvalCase evalCase, List<Retrieved> retrieved, boolean refused, String refusalReason,
        String answer, List<String> citedDocuments, Double topRelevance, long searchMillis, long firstEventMillis,
        long askMillis) {

    public record Retrieved(String document, String content) {
    }

    public EvalOutcome {
        retrieved = List.copyOf(retrieved);
        citedDocuments = List.copyOf(citedDocuments);
    }
}
