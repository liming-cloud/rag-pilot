package com.liming.rag.answering.infrastructure.persistence;

/** 回答引用的一条出处（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class CitationDO {

    Long answerId;
    Integer citationIndex;
    Long chunkId;
    Long documentId;
    String documentName;
    String headingPath;
}
