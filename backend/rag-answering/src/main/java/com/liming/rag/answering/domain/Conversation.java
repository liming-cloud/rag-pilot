package com.liming.rag.answering.domain;

import com.liming.rag.shared.AggregateRoot;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 员工端会话聚合（#107、D-036）：属于提问人本人，记下标题和会话里选中的知识库。轮次（{@link ConversationTurn}）
 * 由仓储按会话存取，不随聚合整体加载。
 *
 * <p>只有本人能操作，别人操作时抛 {@link NotOwnerException}，对外按“会话不存在”处理。知识库是否有权限
 * 由应用层按当前授权过滤后再交给聚合，聚合只保证至少选一个。
 */
public final class Conversation implements AggregateRoot {

    /** 标题长度上限，与数据库列宽一致。 */
    public static final int MAX_TITLE_LENGTH = 40;

    /** 用问题当标题时截取的字数，侧栏一行放得下。 */
    static final int QUESTION_TITLE_LENGTH = 20;

    static final String NEW_TITLE = "新对话";

    /** 标题从哪来：员工改过的不再自动改；模型生成的标题只替换问题截断。 */
    public enum TitleSource {
        NEW,
        QUESTION,
        MODEL,
        USER
    }

    /** 操作人不是会话的主人。对外按“会话不存在”处理，不暴露别人的会话是否存在。 */
    public static final class NotOwnerException extends RuntimeException {

        NotOwnerException() {
            super("只能操作自己的会话");
        }
    }

    private final Long id;
    private final TenantId tenantId;
    private final UserId owner;
    private String title;
    private TitleSource titleSource;
    private List<Long> knowledgeBaseIds;
    private Instant lastAskedAt;

    private Conversation(Long id, TenantId tenantId, UserId owner, String title, TitleSource titleSource,
            List<Long> knowledgeBaseIds, Instant lastAskedAt) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.owner = Objects.requireNonNull(owner);
        this.title = Objects.requireNonNull(title);
        this.titleSource = Objects.requireNonNull(titleSource);
        this.knowledgeBaseIds = distinct(knowledgeBaseIds);
        this.lastAskedAt = lastAskedAt;
    }

    /**
     * @throws IllegalArgumentException 一个知识库都没选
     */
    public static Conversation start(TenantId tenantId, UserId owner, List<Long> knowledgeBaseIds) {
        return new Conversation(null, tenantId, owner, NEW_TITLE, TitleSource.NEW, knowledgeBaseIds, Instant.now());
    }

    /** 仓储从数据库恢复聚合时使用。 */
    public static Conversation restore(long id, TenantId tenantId, UserId owner, String title, TitleSource titleSource,
            List<Long> knowledgeBaseIds, Instant lastAskedAt) {
        return new Conversation(id, tenantId, owner, title, titleSource, knowledgeBaseIds, lastAskedAt);
    }

    public Conversation withId(long newId) {
        return new Conversation(newId, tenantId, owner, title, titleSource, knowledgeBaseIds, lastAskedAt);
    }

    /** @throws NotOwnerException 不是本人 */
    public void requireOwner(UserId by) {
        if (!owner.equals(by)) {
            throw new NotOwnerException();
        }
    }

    /**
     * @throws NotOwnerException 不是本人
     * @throws IllegalArgumentException 标题为空或超过 {@value #MAX_TITLE_LENGTH} 字
     */
    public void rename(UserId by, String newTitle) {
        requireOwner(by);
        var value = newTitle == null ? "" : newTitle.strip();
        if (value.isEmpty() || value.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("标题不能为空，且不超过 " + MAX_TITLE_LENGTH + " 个字");
        }
        this.title = value;
        this.titleSource = TitleSource.USER;
    }

    /**
     * 改选知识库，从下一个问题起生效。
     *
     * @throws NotOwnerException 不是本人
     * @throws IllegalArgumentException 一个知识库都没选
     */
    public void selectKnowledgeBases(UserId by, List<Long> ids) {
        requireOwner(by);
        this.knowledgeBaseIds = distinct(ids);
    }

    /** 记下一次提问：更新最后提问时间（保留期从它算起）；第一次提问时用问题截断当标题。 */
    public void asked(String question, Instant at) {
        this.lastAskedAt = Objects.requireNonNull(at);
        if (titleSource == TitleSource.NEW) {
            var text = question.strip();
            this.title = text.length() > QUESTION_TITLE_LENGTH ? text.substring(0, QUESTION_TITLE_LENGTH) + "…" : text;
            this.titleSource = TitleSource.QUESTION;
        }
    }

    /** 模型在后台生成的标题，只替换问题截断；员工改过的不动，空的或太长的不采用。 */
    public void titleGenerated(String generated) {
        var value = generated == null ? "" : generated.strip();
        if (titleSource != TitleSource.QUESTION || value.isEmpty() || value.length() > MAX_TITLE_LENGTH) {
            return;
        }
        this.title = value;
        this.titleSource = TitleSource.MODEL;
    }

    private static List<Long> distinct(List<Long> ids) {
        var values = ids == null ? List.<Long>of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException("至少要选一个知识库");
        }
        return values;
    }

    public Long id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UserId owner() {
        return owner;
    }

    public String title() {
        return title;
    }

    public TitleSource titleSource() {
        return titleSource;
    }

    public List<Long> knowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public Instant lastAskedAt() {
        return lastAskedAt;
    }
}
