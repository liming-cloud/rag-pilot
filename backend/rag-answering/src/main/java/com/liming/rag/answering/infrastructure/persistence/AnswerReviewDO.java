package com.liming.rag.answering.infrastructure.persistence;

import java.time.OffsetDateTime;

/** 审核列表和运营明细的一行，引用的文档名用 \u001F 连接（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class AnswerReviewDO {

    Long id;
    Long userId;
    String question;
    String answerText;
    Boolean refused;
    String refusalReason;
    Double topRelevance;
    String rating;
    String feedbackComment;
    OffsetDateTime createdAt;
    String rewrittenQuestion;
    String reviewStatus;
    String resolutionNote;
    OffsetDateTime resolvedAt;
    String citedDocuments;
}
