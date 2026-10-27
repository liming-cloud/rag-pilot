package com.liming.rag.answering.api;

import java.util.List;

/**
 * 一次问答的结果。拒答时 {@code text} 是给用户看的说明，{@code citations} 为空。
 *
 * @param id 回答记录的标识，用于点赞点踩；记录之前为 null
 * @param topRelevance 重排后最高的相关度分数，没有检索结果时为 null；用于评测时校准拒答门槛
 * @param rewrittenQuestion 低分时系统改写后的问题，没有改写时为 null
 * @param timings 各阶段耗时，用于评测和排查延迟
 */
public record Answer(Long id, String text, boolean refused, RefusalReason refusalReason, List<Citation> citations,
        Double topRelevance, String rewrittenQuestion, Timings timings) {

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
        return new Answer(newId, text, refused, refusalReason, citations, topRelevance, rewrittenQuestion, timings);
    }

    public Answer withTimings(Timings newTimings) {
        return new Answer(id, text, refused, refusalReason, citations, topRelevance, rewrittenQuestion, newTimings);
    }

    public Answer withRewrittenQuestion(String question) {
        return new Answer(id, text, refused, refusalReason, citations, topRelevance, question, timings);
    }
}
