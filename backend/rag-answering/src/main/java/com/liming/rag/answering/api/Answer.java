package com.liming.rag.answering.api;

import java.util.List;

/**
 * 一次问答的结果。拒答时 {@code text} 是给用户看的说明，{@code citations} 为空。
 *
 * @param topRelevance 重排后最高的相关度分数，没有检索结果时为 null；用于评测时校准拒答门槛
 */
public record Answer(String text, boolean refused, RefusalReason refusalReason, List<Citation> citations,
        Double topRelevance) {

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
        return new Answer(text, false, null, List.copyOf(citations), topRelevance);
    }

    public static Answer refused(RefusalReason reason, Double topRelevance) {
        return new Answer("知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。", true, reason, List.of(),
                topRelevance);
    }
}
