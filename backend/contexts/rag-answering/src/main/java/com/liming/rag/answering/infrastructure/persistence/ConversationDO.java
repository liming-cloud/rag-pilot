package com.liming.rag.answering.infrastructure.persistence;

import java.time.OffsetDateTime;

/** 会话，另带主线最后一轮的上一轮（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class ConversationDO {

    Long id;
    Long tenantId;
    Long userId;
    Long version;
    String title;
    String titleSource;
    Long[] knowledgeBaseIds;
    OffsetDateTime lastAskedAt;
    Long lastTurnId;
    Long lastTurnParentId;
}
