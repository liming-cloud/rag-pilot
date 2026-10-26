package com.liming.rag.knowledge.domain;

/**
 * 文档切出的一个检索单元。
 *
 * @param ordinal 在文档内的顺序，从 0 开始
 * @param headingPath “文档名 &gt; 章节 &gt; 小节”形式的位置，用于引用展示和嵌入时补充上下文
 * @param content 分块正文
 * @param tokenCount 估算的 token 数，见 {@link TokenEstimator}
 */
public record Chunk(int ordinal, String headingPath, String content, int tokenCount) {
}
