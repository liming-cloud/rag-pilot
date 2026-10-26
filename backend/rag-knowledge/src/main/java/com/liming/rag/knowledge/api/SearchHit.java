package com.liming.rag.knowledge.api;

/**
 * 一条检索结果，供问答模块组织证据和引用。
 *
 * @param score 融合后的相关度，只用于排序，不同查询之间不可比较
 */
public record SearchHit(long chunkId, long documentId, String documentName, String headingPath, String content,
        double score) {
}
