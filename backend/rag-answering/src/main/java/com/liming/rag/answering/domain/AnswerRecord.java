package com.liming.rag.answering.domain;

import com.liming.rag.shared.AggregateRoot;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Objects;

/**
 * 回答记录聚合：一次问答的结果、提问人给它的评价，以及管理员的处理状态。问题、回答、引用写入后不再变；
 * 评价和处理状态可以改。
 *
 * <p>只有提问人本人能评价，别人的评价会被拒绝。处理只有管理员能做，角色由接口层的 Sa-Token 注解校验。
 * 仓储只加载评价和处理相关的字段。
 */
public final class AnswerRecord implements AggregateRoot {

    /** 留言和处理说明的长度上限，与数据库列宽一致。 */
    public static final int MAX_COMMENT_LENGTH = 500;

    public enum Rating {
        UP,
        DOWN
    }

    /** 管理员是否已处理这条记录（补文档、改文档，或确认无需处理）。 */
    public enum ReviewStatus {
        OPEN,
        RESOLVED
    }

    /** 评价人不是提问人。对外按“回答不存在”处理，不暴露别人的回答是否存在。 */
    public static final class NotAskerException extends RuntimeException {

        NotAskerException() {
            super("只有提问人能评价这条回答");
        }
    }

    private final long id;
    private final TenantId tenantId;
    private final UserId asker;
    private final String question;
    private Rating rating;
    private String comment;
    private ReviewStatus reviewStatus;
    private String resolutionNote;
    private UserId resolvedBy;

    private AnswerRecord(long id, TenantId tenantId, UserId asker, String question, Rating rating, String comment,
            ReviewStatus reviewStatus, String resolutionNote) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.asker = Objects.requireNonNull(asker);
        this.question = question;
        this.rating = rating;
        this.comment = comment;
        this.reviewStatus = Objects.requireNonNull(reviewStatus);
        this.resolutionNote = resolutionNote;
    }

    /** 仓储从数据库恢复聚合时使用，只关心评价时用这个。 */
    public static AnswerRecord restore(long id, TenantId tenantId, UserId asker, Rating rating, String comment) {
        return restore(id, tenantId, asker, null, rating, comment, ReviewStatus.OPEN, null);
    }

    /** 仓储从数据库恢复聚合时使用，带上问题和处理状态。 */
    public static AnswerRecord restore(long id, TenantId tenantId, UserId asker, String question, Rating rating,
            String comment, ReviewStatus reviewStatus, String resolutionNote) {
        return new AnswerRecord(id, tenantId, asker, question, rating, comment, reviewStatus, resolutionNote);
    }

    /**
     * @throws NotAskerException 评价人不是提问人
     * @throws IllegalArgumentException 留言超过 {@value #MAX_COMMENT_LENGTH} 字
     */
    public void rate(UserId by, Rating newRating, String newComment) {
        requireAsker(by);
        this.rating = Objects.requireNonNull(newRating, "评价不能为空");
        this.comment = trimmed(newComment, "留言");
    }

    /** 撤销评价。 */
    public void clearRating(UserId by) {
        requireAsker(by);
        this.rating = null;
        this.comment = null;
    }

    /**
     * 标记为已处理，或重新打开。重新打开时清掉处理说明和处理人。
     *
     * @throws IllegalArgumentException 处理说明超过 {@value #MAX_COMMENT_LENGTH} 字
     */
    public void review(UserId by, ReviewStatus newStatus, String note) {
        Objects.requireNonNull(by);
        this.reviewStatus = Objects.requireNonNull(newStatus, "处理状态不能为空");
        if (newStatus == ReviewStatus.RESOLVED) {
            this.resolutionNote = trimmed(note, "处理说明");
            this.resolvedBy = by;
        } else {
            this.resolutionNote = null;
            this.resolvedBy = null;
        }
    }

    private void requireAsker(UserId by) {
        if (!asker.equals(by)) {
            throw new NotAskerException();
        }
    }

    private static String trimmed(String text, String label) {
        var value = text == null ? "" : text.strip();
        if (value.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException(label + "不能超过 " + MAX_COMMENT_LENGTH + " 个字");
        }
        return value.isEmpty() ? null : value;
    }

    public long id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    /** 员工原来的问题；只按评价加载时为 null。 */
    public String question() {
        return question;
    }

    public Rating rating() {
        return rating;
    }

    public String comment() {
        return comment;
    }

    public ReviewStatus reviewStatus() {
        return reviewStatus;
    }

    public String resolutionNote() {
        return resolutionNote;
    }

    /** 处理人；未处理或重新打开后为 null。 */
    public UserId resolvedBy() {
        return resolvedBy;
    }
}
