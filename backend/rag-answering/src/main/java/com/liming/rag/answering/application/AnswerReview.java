package com.liming.rag.answering.application;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 管理员查看的一条回答记录：问了什么、答了什么、员工怎么评价、引用了哪些文档。
 *
 * @param askerName 提问人的显示名；账号不存在时为 null
 * @param rating UP、DOWN，没有评价时为 null
 * @param citedDocuments 引用的文档名（当时的名字），去重
 * @param rewrittenQuestion 低分时系统改写后的问题，没有改写时为 null
 * @param reviewStatus OPEN（待处理）或 RESOLVED（已处理）
 * @param resolutionNote 管理员的处理说明
 * @param resolvedAt 标记已处理的时间，未处理时为 null
 */
public record AnswerReview(long id, long userId, String askerName, String question, String answerText, boolean refused,
        String refusalReason, Double topRelevance, String rating, String comment, OffsetDateTime createdAt,
        List<String> citedDocuments, String rewrittenQuestion, String reviewStatus, String resolutionNote,
        OffsetDateTime resolvedAt) {

    public AnswerReview withAskerName(String name) {
        return new AnswerReview(id, userId, name, question, answerText, refused, refusalReason, topRelevance, rating,
                comment, createdAt, citedDocuments, rewrittenQuestion, reviewStatus, resolutionNote, resolvedAt);
    }

    /** 查看范围。 */
    public enum Filter {
        /** 被点踩的。 */
        DISLIKED,
        /** 答不上来的（拒答）。 */
        REFUSED,
        ALL
    }

    /** 处理状态的筛选。 */
    public enum Status {
        OPEN,
        RESOLVED,
        ALL
    }

    /**
     * 回答记录的筛选（D-039）。
     *
     * @param since 只看这个时间以后的，为 null 不限
     * @param keyword 匹配问题，为 null 不筛
     */
    public record Query(Filter filter, Status status, java.time.OffsetDateTime since, String keyword,
            Paged.Request page) {
    }
}
