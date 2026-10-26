package com.liming.rag.answering.api;

/**
 * 回答中 [index] 对应的出处。
 *
 * @param excerpt 分块原文，用于“查看引用原文”
 */
public record Citation(int index, long chunkId, long documentId, String documentName, String headingPath,
        String excerpt) {
}
