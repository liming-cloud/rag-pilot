package com.liming.rag.knowledge.application;

/**
 * 入库管道里分块 Document 的元数据键。Spring AI 的 Document 只在管道内部使用，元数据键集中在这里，
 * 不在各处手写字符串。
 */
final class ChunkMetadata {

    /** 在文档内的顺序，从 0 开始，Integer。 */
    static final String ORDINAL = "ordinal";
    /** “文档名 &gt; 章节 &gt; 小节”，String。 */
    static final String HEADING_PATH = "headingPath";
    /** 估算的 token 数，Integer。 */
    static final String TOKEN_COUNT = "tokenCount";

    private ChunkMetadata() {
    }
}
