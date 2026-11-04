package com.liming.rag.answering.infrastructure.persistence;

import java.time.OffsetDateTime;

/** 知识缺口要归类的一条记录，引用的文档名用 \u001F 连接（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class GapRecordDO {

    Long id;
    Long userId;
    String question;
    OffsetDateTime createdAt;
    Boolean refused;
    String feedbackComment;
    String reviewStatus;
    Long[] knowledgeBaseIds;
    String citedDocuments;
    Float[] questionEmbedding;
}
