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
 * @param stages 服务端各阶段耗时；没有时为 null
 * @param rewrittenQuestion 低分时系统改写后的问题，没有改写时为 null
 */
public record EvalOutcome(EvalCase evalCase, List<Retrieved> retrieved, boolean refused, String refusalReason,
        String answer, List<String> citedDocuments, Double topRelevance, long searchMillis, long firstEventMillis,
        long askMillis, Stages stages, String rewrittenQuestion) {

    /** 服务端各阶段耗时（毫秒），没走到的阶段为 null。 */
    public record Stages(Long retrievalMillis, Long rerankMillis, Long modelFirstTokenMillis, Long rewriteMillis) {

        public Stages(Long retrievalMillis, Long rerankMillis, Long modelFirstTokenMillis) {
            this(retrievalMillis, rerankMillis, modelFirstTokenMillis, null);
        }
    }

    public EvalOutcome(EvalCase evalCase, List<Retrieved> retrieved, boolean refused, String refusalReason,
            String answer, List<String> citedDocuments, Double topRelevance, long searchMillis, long firstEventMillis,
            long askMillis, Stages stages) {
        this(evalCase, retrieved, refused, refusalReason, answer, citedDocuments, topRelevance, searchMillis,
                firstEventMillis, askMillis, stages, null);
    }

    public EvalOutcome(EvalCase evalCase, List<Retrieved> retrieved, boolean refused, String refusalReason,
            String answer, List<String> citedDocuments, Double topRelevance, long searchMillis, long firstEventMillis,
            long askMillis) {
        this(evalCase, retrieved, refused, refusalReason, answer, citedDocuments, topRelevance, searchMillis,
                firstEventMillis, askMillis, null, null);
    }

    public record Retrieved(String document, String content) {
    }

    public EvalOutcome {
        retrieved = List.copyOf(retrieved);
        citedDocuments = List.copyOf(citedDocuments);
    }
}
