package com.liming.rag.knowledge.infrastructure.persistence;

/** 召回分块的详情；accessibleContents 只取 chunk_id 和 content（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class ChunkHitDO {

    Long chunkId;
    Long documentId;
    String documentName;
    String headingPath;
    String content;
}
