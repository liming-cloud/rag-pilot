package com.liming.rag.evaluation.api;

import java.util.List;

/**
 * 一道评测题。
 *
 * @param expectedDocuments 可回答题的原文所在文档；有多篇时任一篇进前 10 算召回，引用任一篇算引用正确
 * @param keywords 可回答题的答案关键词，回答或分块里命中任一即可；开源数据集没有关键词，只看文档并交给答案评判
 * @param restricted 应拒答题的答案是否只存在于无权访问的知识库（越权检查）
 * @param style 问法：原话、改写、口语、错字、真实等，报告按它分别统计；不填时是“原话”
 * @param referenceAnswer 参考答案，有它时由评判模型判断回答对不对；没有时为 null
 */
public record EvalCase(String id, String question, Kind kind, List<String> expectedDocuments, List<String> keywords,
        boolean restricted, String style, String referenceAnswer) {

    public static final String DEFAULT_STYLE = "原话";

    public enum Kind {
        ANSWERABLE, UNANSWERABLE
    }

    public EvalCase {
        expectedDocuments = expectedDocuments == null ? List.of() : List.copyOf(expectedDocuments);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        style = style == null || style.isBlank() ? DEFAULT_STYLE : style;
        referenceAnswer = referenceAnswer == null || referenceAnswer.isBlank() ? null : referenceAnswer;
    }

    public EvalCase(String id, String question, Kind kind, String expectedDocument, List<String> keywords,
            boolean restricted, String style) {
        this(id, question, kind, expectedDocument == null ? List.of() : List.of(expectedDocument), keywords, restricted,
                style, null);
    }

    public EvalCase(String id, String question, Kind kind, String expectedDocument, List<String> keywords,
            boolean restricted) {
        this(id, question, kind, expectedDocument, keywords, restricted, DEFAULT_STYLE);
    }

    /** 没有关键词时只看文档，答案对不对交给评判。 */
    boolean matches(String text) {
        return keywords.isEmpty() || text != null && keywords.stream().anyMatch(text::contains);
    }
}
