package com.liming.rag.answering.api;

import java.util.List;

/**
 * 一次问答的结果。拒答时 {@code text} 是给用户看的说明，{@code citations} 为空。
 *
 * @param id 回答记录的标识，用于点赞点踩；记录之前为 null
 * @param topRelevance 重排后最高的相关度分数，没有检索结果时为 null；用于评测时校准拒答门槛
 * @param rewrite 低分时的改写情况，没有走到改写这一步时为 null
 * @param timings 各阶段耗时，用于评测和排查延迟
 */
public record Answer(Long id, String text, boolean refused, RefusalReason refusalReason, List<Citation> citations,
        Double topRelevance, Rewrite rewrite, Timings timings) {

    /**
     * 低分时改写问题的情况，用于评测和排查。
     *
     * @param question 改写后的问题，只有 {@link Outcome#REWRITTEN} 时有值
     * @param outputChars 模型改写输出的字数（截取前），没有调用模型时为 0
     * @param modelMillis 模型改写的耗时，没有调用模型时为 null
     * @param retrievalMillis 用改写后的问题重新检索的耗时，没有重新检索时为 null
     * @param rerankMillis 重新重排的耗时，没有重新重排时为 null
     */
    public record Rewrite(Outcome outcome, String question, int outputChars, Long modelMillis, Long retrievalMillis,
            Long rerankMillis) {

        public enum Outcome {
            /** 改写前最高分低于下限，判定改写也救不回，直接拒答。 */
            SKIPPED_BELOW_FLOOR,
            /** 模型改写超时，放弃改写。 */
            TIMED_OUT,
            /** 模型没给出有效改写（空、NO_ANSWER、太长或和原问题一样）。 */
            NO_CHANGE,
            /** 已改写并用新问题重新检索、重排。 */
            REWRITTEN
        }
    }

    /**
     * 各阶段耗时（毫秒）。没走到的阶段为 null：检索不到时没有重排，拒答在生成前时没有生成。
     *
     * @param retrievalMillis 向量化问题 + 混合检索
     * @param rerankMillis 重排
     * @param firstTokenMillis 从开始生成到模型给出第一段文字
     * @param generationMillis 从开始生成到生成完成
     * @param rewriteMillis 低分时尝试改写问题并重新检索、重排的耗时（包括模型没给出有效改写的情况），没有尝试时为 null
     */
    public record Timings(long retrievalMillis, Long rerankMillis, Long firstTokenMillis, Long generationMillis,
            Long rewriteMillis) {
    }

    public enum RefusalReason {
        /** 指定知识库里没有检索到任何内容。 */
        NO_EVIDENCE,
        /** 检索到的内容与问题相关度都低于阈值。 */
        LOW_RELEVANCE,
        /** 模型判断资料不足以回答。 */
        MODEL_DECLINED,
        /** 模型给出的回答没有可核对的引用。 */
        UNCITED
    }

    public static Answer answered(String text, List<Citation> citations, double topRelevance) {
        return new Answer(null, text, false, null, List.copyOf(citations), topRelevance, null, null);
    }

    public static Answer refused(RefusalReason reason, Double topRelevance) {
        return new Answer(null, "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。", true, reason, List.of(),
                topRelevance, null, null);
    }

    public Answer withId(long newId) {
        return new Answer(newId, text, refused, refusalReason, citations, topRelevance, rewrite, timings);
    }

    public Answer withTimings(Timings newTimings) {
        return new Answer(id, text, refused, refusalReason, citations, topRelevance, rewrite, newTimings);
    }

    public Answer withRewrite(Rewrite newRewrite) {
        return new Answer(id, text, refused, refusalReason, citations, topRelevance, newRewrite, timings);
    }

    /** 改写后的问题；没有改写时为 null。 */
    public String rewrittenQuestion() {
        return rewrite == null ? null : rewrite.question();
    }
}
