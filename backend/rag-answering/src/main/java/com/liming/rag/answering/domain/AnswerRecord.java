package com.liming.rag.answering.domain;

import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import java.util.Objects;

/**
 * 回答记录聚合：一次问答的结果，以及提问人给它的评价。问题、回答、引用写入后不再变；只有评价可以改。
 *
 * <p>只有提问人本人能评价，别人的评价会被拒绝。仓储只加载评价相关的字段。
 */
public final class AnswerRecord {

    /** 留言长度上限，与数据库列宽一致。 */
    public static final int MAX_COMMENT_LENGTH = 500;

    public enum Rating {
        UP,
        DOWN
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
    private Rating rating;
    private String comment;

    private AnswerRecord(long id, TenantId tenantId, UserId asker, Rating rating, String comment) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.asker = Objects.requireNonNull(asker);
        this.rating = rating;
        this.comment = comment;
    }

    /** 仓储从数据库恢复聚合时使用。 */
    public static AnswerRecord restore(long id, TenantId tenantId, UserId asker, Rating rating, String comment) {
        return new AnswerRecord(id, tenantId, asker, rating, comment);
    }

    /**
     * @throws NotAskerException 评价人不是提问人
     * @throws IllegalArgumentException 留言超过 {@value #MAX_COMMENT_LENGTH} 字
     */
    public void rate(UserId by, Rating newRating, String newComment) {
        requireAsker(by);
        var trimmed = newComment == null ? "" : newComment.strip();
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("留言不能超过 " + MAX_COMMENT_LENGTH + " 个字");
        }
        this.rating = Objects.requireNonNull(newRating, "评价不能为空");
        this.comment = trimmed.isEmpty() ? null : trimmed;
    }

    /** 撤销评价。 */
    public void clearRating(UserId by) {
        requireAsker(by);
        this.rating = null;
        this.comment = null;
    }

    private void requireAsker(UserId by) {
        if (!asker.equals(by)) {
            throw new NotAskerException();
        }
    }

    public long id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public Rating rating() {
        return rating;
    }

    public String comment() {
        return comment;
    }
}
