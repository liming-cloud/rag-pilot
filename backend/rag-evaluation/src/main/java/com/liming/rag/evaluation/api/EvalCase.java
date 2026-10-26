package com.liming.rag.evaluation.api;

import java.util.List;

/**
 * 一道评测题。
 *
 * @param expectedDocument 可回答题应当引用的文档名
 * @param keywords 可回答题的答案关键词，回答或分块里命中任一即可
 * @param restricted 应拒答题的答案是否只存在于无权访问的知识库（越权检查）
 */
public record EvalCase(String id, String question, Kind kind, String expectedDocument, List<String> keywords,
        boolean restricted) {

    public enum Kind {
        ANSWERABLE, UNANSWERABLE
    }

    public EvalCase {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    boolean matches(String text) {
        return text != null && keywords.stream().anyMatch(text::contains);
    }
}
