package com.liming.rag.knowledge.infrastructure.persistence;

import org.jspecify.annotations.NullUnmarked;

/** 知识库列表的一行（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
@NullUnmarked
final class KnowledgeBaseSummaryDO {

    Long id;
    String name;
    Long documentCount;
    Long failedCount;
    Long expiringCount;
    Long expiredCount;
    Long roleGrantCount;
    Long memberCount;
}
