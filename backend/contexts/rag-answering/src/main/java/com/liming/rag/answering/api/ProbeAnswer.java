package com.liming.rag.answering.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@link AnswerProbe} 的结果。
 *
 * @param refusalReason 拒答原因（如 NO_EVIDENCE、LOW_RELEVANCE），没拒答时为 null
 * @param passages 重排后相关度最高的几段原文（不论是否达到门槛，最多和送给模型的条数上限一样多），按相关度从高到低；
 *     回答引用的原文都在其中
 */
public record ProbeAnswer(String text, boolean refused, @Nullable String refusalReason, List<ProbePassage> passages) {

    public ProbeAnswer {
        passages = List.copyOf(passages);
    }

    /**
     * 召回的一段原文。
     *
     * @param excerpt 原文开头，最多 {@value #EXCERPT_CHARS} 个字
     * @param relevance 重排分数
     * @param cited 回答引用了这一段
     */
    public record ProbePassage(long documentId, String documentName, String headingPath, String excerpt,
            double relevance, boolean cited) {

        public static final int EXCERPT_CHARS = 300;
    }
}
