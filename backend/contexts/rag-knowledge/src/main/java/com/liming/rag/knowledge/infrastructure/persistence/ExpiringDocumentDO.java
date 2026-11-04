package com.liming.rag.knowledge.infrastructure.persistence;

import java.time.LocalDate;
import org.jspecify.annotations.NullUnmarked;

/** 到期提醒里的一份文档（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
@NullUnmarked
final class ExpiringDocumentDO {

    Long id;
    Long knowledgeBaseId;
    String knowledgeBaseName;
    String filename;
    LocalDate expiresOn;
    String validity;
}
