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
 * @param rewrite 低分时的改写情况，没有走到改写时为 null
 * @param judgement 评判模型对回答的判断；没有参考答案、拒答了或没开评判时为 null
 * @param discardedReply 拒答时模型已流式输出、随后被拒答替换掉的原文；没有时为 null
 */
public record EvalOutcome(EvalCase evalCase, List<Retrieved> retrieved, boolean refused, String refusalReason,
        String answer, List<String> citedDocuments, Double topRelevance, long searchMillis, long firstEventMillis,
        long askMillis, Stages stages, Rewrite rewrite, Judgement judgement, String discardedReply) {

    /**
     * 评判模型对照参考答案给出的结论。
     *
     * @param verdict CORRECT、PARTIAL 或 WRONG
     */
    public record Judgement(String verdict, String reason) {
    }

    public EvalOutcome(EvalCase evalCase, List<Retrieved> retrieved, boolean refused, String refusalReason,
            String answer, List<String> citedDocuments, Double topRelevance, long searchMillis, long firstEventMillis,
            long askMillis, Stages stages, Rewrite rewrite) {
        this(evalCase, retrieved, refused, refusalReason, answer, citedDocuments, topRelevance, searchMillis,
                firstEventMillis, askMillis, stages, rewrite, null);
    }

    public EvalOutcome(EvalCase evalCase, List<Retrieved> retrieved, boolean refused, String refusalReason,
            String answer, List<String> citedDocuments, Double topRelevance, long searchMillis, long firstEventMillis,
            long askMillis, Stages stages, Rewrite rewrite, Judgement judgement) {
        this(evalCase, retrieved, refused, refusalReason, answer, citedDocuments, topRelevance, searchMillis,
                firstEventMillis, askMillis, stages, rewrite, judgement, null);
    }

    /** 补上评判结果。 */
    public EvalOutcome withJudgement(Judgement judgement) {
        return new EvalOutcome(evalCase, retrieved, refused, refusalReason, answer, citedDocuments, topRelevance,
                searchMillis, firstEventMillis, askMillis, stages, rewrite, judgement, discardedReply);
    }

    /** 补上被拒答替换掉的模型原文。 */
    public EvalOutcome withDiscardedReply(String discardedReply) {
        return new EvalOutcome(evalCase, retrieved, refused, refusalReason, answer, citedDocuments, topRelevance,
                searchMillis, firstEventMillis, askMillis, stages, rewrite, judgement, discardedReply);
    }

    /**
     * 低分改写的情况，与服务端 Answer.Rewrite 对应。
     *
     * @param outcome SKIPPED_BELOW_FLOOR、TIMED_OUT、NO_CHANGE 或 REWRITTEN
     * @param question 改写后的问题，只有 REWRITTEN 时有值
     * @param outputChars 模型改写输出的字数
     */
    public record Rewrite(String outcome, String question, int outputChars, Long modelMillis, Long retrievalMillis,
            Long rerankMillis) {
    }

    /** 改写后的问题；没有改写时为 null。 */
    public String rewrittenQuestion() {
        return rewrite == null ? null : rewrite.question();
    }

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
                firstEventMillis, askMillis, stages, null, null);
    }

    public EvalOutcome(EvalCase evalCase, List<Retrieved> retrieved, boolean refused, String refusalReason,
            String answer, List<String> citedDocuments, Double topRelevance, long searchMillis, long firstEventMillis,
            long askMillis) {
        this(evalCase, retrieved, refused, refusalReason, answer, citedDocuments, topRelevance, searchMillis,
                firstEventMillis, askMillis, null, null, null);
    }

    public record Retrieved(String document, String content) {
    }

    public EvalOutcome {
        retrieved = List.copyOf(retrieved);
        citedDocuments = List.copyOf(citedDocuments);
    }
}
