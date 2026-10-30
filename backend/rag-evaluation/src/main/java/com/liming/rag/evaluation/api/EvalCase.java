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
 * @param history 同一个会话里先问的问题（不计分）；有它时这道题按会话提问，测持续对话（D-036）
 * @param searchAs 追问题补全后的独立问法，只用来检查召回（检索规划给出的检索词不对外）；没有时用 question
 */
public record EvalCase(String id, String question, Kind kind, List<String> expectedDocuments, List<String> keywords,
        boolean restricted, String style, String referenceAnswer, List<String> history, String searchAs) {

    public static final String DEFAULT_STYLE = "原话";

    /** SMALL_TALK：寒暄，回复不能带引用、不能有事实（D-036），不计入可回答和应拒答的指标。 */
    public enum Kind {
        ANSWERABLE, UNANSWERABLE, SMALL_TALK
    }

    public EvalCase {
        expectedDocuments = expectedDocuments == null ? List.of() : List.copyOf(expectedDocuments);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        style = style == null || style.isBlank() ? DEFAULT_STYLE : style;
        referenceAnswer = referenceAnswer == null || referenceAnswer.isBlank() ? null : referenceAnswer;
        history = history == null ? List.of() : List.copyOf(history);
        searchAs = searchAs == null || searchAs.isBlank() ? null : searchAs;
    }

    public EvalCase(String id, String question, Kind kind, List<String> expectedDocuments, List<String> keywords,
            boolean restricted, String style, String referenceAnswer) {
        this(id, question, kind, expectedDocuments, keywords, restricted, style, referenceAnswer, List.of(), null);
    }

    /** 检查召回时用的问法。 */
    public String searchText() {
        return searchAs == null ? question : searchAs;
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
