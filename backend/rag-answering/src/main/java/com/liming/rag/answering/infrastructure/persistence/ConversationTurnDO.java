package com.liming.rag.answering.infrastructure.persistence;

import java.time.OffsetDateTime;

/** 会话的一轮（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class ConversationTurnDO {

    Long id;
    Long parentTurnId;
    String question;
    String status;
    Long answerRecordId;
    Long[] knowledgeBaseIds;
    OffsetDateTime askedAt;
}
