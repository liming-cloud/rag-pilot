package com.liming.rag.answering.application;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 管理员查看的一条回答记录：问了什么、答了什么、员工怎么评价、引用了哪些文档。
 *
 * @param rating UP、DOWN，没有评价时为 null
 * @param citedDocuments 引用的文档名（当时的名字），去重
 * @param rewrittenQuestion 低分时系统改写后的问题，没有改写时为 null
 * @param reviewStatus OPEN（待处理）或 RESOLVED（已处理）
 * @param resolutionNote 管理员的处理说明
 * @param resolvedAt 标记已处理的时间，未处理时为 null
 */
public record AnswerReview(long id, long userId, String question, String answerText, boolean refused,
        String refusalReason, Double topRelevance, String rating, String comment, OffsetDateTime createdAt,
        List<String> citedDocuments, String rewrittenQuestion, String reviewStatus, String resolutionNote,
        OffsetDateTime resolvedAt) {

    /** 查看范围。 */
    public enum Filter {
        /** 被点踩的。 */
        DISLIKED,
        /** 答不上来的（拒答）。 */
        REFUSED,
        ALL
    }
}
