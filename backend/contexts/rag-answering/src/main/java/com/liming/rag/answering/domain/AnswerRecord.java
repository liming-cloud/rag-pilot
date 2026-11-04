package com.liming.rag.answering.domain;

import com.liming.rag.shared.AggregateRoot;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.io.Serial;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 回答记录聚合：一次问答的问题和回答（{@link Reply}）、实际检索的知识库、提问人给它的评价，以及管理员的处理状态。
 * 问题、回答、引用、检索范围写入后不再变；评价和处理状态可以改（#136：新建也经过聚合，加载时带全部字段）。
 *
 * <p>只有提问人本人能评价，别人的评价会被拒绝。处理只有管理员能做，权限点由接口层的 Sa-Token 注解校验。
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

    /**
     * 当时给员工的回答。拒答时 {@code text} 是拒答话术，{@code citations} 为空。
     *
     * @param citations 引用的出处；原文不存在这里，展示时按当前授权读取（D-036）
     * @param topRelevance 重排后最高的相关度分数，没有检索结果时为 null
     * @param rewrittenQuestion 低分时改写后的问题，没有改写时为 null
     */
    public record Reply(String text, boolean refused, @Nullable RefusalReason refusalReason, List<Citation> citations,
            @Nullable Double topRelevance, @Nullable String rewrittenQuestion) {

        public Reply {
            Objects.requireNonNull(text, "回答不能为空");
            citations = List.copyOf(citations);
        }
    }

    /** 评价人不是提问人。对外按“回答不存在”处理，不暴露别人的回答是否存在。 */
    public static final class NotAskerException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        NotAskerException() {
            super("只有提问人能评价这条回答");
        }
    }

    private final Long id;
    private final TenantId tenantId;
    private final UserId asker;
    private final String question;
    private final Reply reply;
    private final List<Long> knowledgeBaseIds;
    private Rating rating;
    private String comment;
    private ReviewStatus reviewStatus;
    private String resolutionNote;
    private @Nullable UserId resolvedBy;

    private AnswerRecord(@Nullable Long id, TenantId tenantId, UserId asker, String question, Reply reply,
            List<Long> knowledgeBaseIds, Rating rating, String comment, ReviewStatus reviewStatus,
            String resolutionNote, UserId resolvedBy) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.asker = Objects.requireNonNull(asker);
        this.question = Objects.requireNonNull(question);
        this.reply = Objects.requireNonNull(reply);
        this.knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
        this.rating = rating;
        this.comment = comment;
        this.reviewStatus = Objects.requireNonNull(reviewStatus);
        this.resolutionNote = resolutionNote;
        this.resolvedBy = resolvedBy;
    }

    /**
     * 记下一次问答：还没有评价，待处理。
     *
     * @param knowledgeBaseIds 这次实际检索的知识库（D-047）；为空表示所选的库都已收回授权
     */
    public static AnswerRecord record(TenantId tenantId, UserId asker, String question, Reply reply,
            List<Long> knowledgeBaseIds) {
        return new AnswerRecord(null, tenantId, asker, question.strip(), reply, knowledgeBaseIds, null, null,
                ReviewStatus.OPEN, null, null);
    }

    /** 仓储从数据库恢复聚合时使用，不做业务校验。 */
    public static AnswerRecord restore(long id, TenantId tenantId, UserId asker, String question, Reply reply,
            List<Long> knowledgeBaseIds, Rating rating, String comment, ReviewStatus reviewStatus,
            String resolutionNote, UserId resolvedBy) {
        return new AnswerRecord(id, tenantId, asker, question, reply, knowledgeBaseIds, rating, comment, reviewStatus,
                resolutionNote, resolvedBy);
    }

    /** 新建后由仓储回填标识。 */
    public AnswerRecord withId(long newId) {
        return new AnswerRecord(newId, tenantId, asker, question, reply, knowledgeBaseIds, rating, comment,
                reviewStatus, resolutionNote, resolvedBy);
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

    /** 新建、还没保存时为 null。 */
    public @Nullable Long id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UserId asker() {
        return asker;
    }

    /** 员工原来的问题。 */
    public String question() {
        return question;
    }

    public Reply reply() {
        return reply;
    }

    /** 这次实际检索的知识库。 */
    public List<Long> knowledgeBaseIds() {
        return knowledgeBaseIds;
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
    public @Nullable UserId resolvedBy() {
        return resolvedBy;
    }
}
