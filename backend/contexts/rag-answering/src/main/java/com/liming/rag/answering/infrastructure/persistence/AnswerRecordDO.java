package com.liming.rag.answering.infrastructure.persistence;

import org.jspecify.annotations.NullUnmarked;

/** 回答记录的全部列，加载聚合用（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
@NullUnmarked
final class AnswerRecordDO {

    Long id;
    Long tenantId;
    Long userId;
    String question;
    String answerText;
    Boolean refused;
    String refusalReason;
    Double topRelevance;
    String rewrittenQuestion;
    Long[] knowledgeBaseIds;
    String rating;
    String feedbackComment;
    String reviewStatus;
    String resolutionNote;
    Long resolvedBy;
}
