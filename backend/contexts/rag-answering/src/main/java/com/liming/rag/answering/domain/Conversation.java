package com.liming.rag.answering.domain;

import com.liming.rag.shared.AggregateRoot;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 员工端会话聚合（#107、D-036）：属于提问人本人，记下标题、会话里选中的知识库和主线的末尾。轮次
 * （{@link ConversationTurn}）由仓储按会话存取，不随聚合整体加载；聚合只记主线最后一轮和它接在哪一轮之后，
 * 据此判断“只能重新生成或修改最后一轮”、算出新的一轮接在哪里（#135）。
 *
 * <p>并发修改按版本号判断（乐观锁）：仓储保存时发现版本已变，抛 {@link ConcurrentChangeException}，这次修改作废。
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

    /** 保存时发现会话已被别的操作改过（版本号不一致）。 */
    public static final class ConcurrentChangeException extends RuntimeException {

        public ConcurrentChangeException() {
            super("会话刚被别的操作改过");
        }
    }

    /**
     * 主线的末尾：最后一轮，以及它接在哪一轮之后（第一轮为 null）。还没有提问时整个为 null。
     */
    public record MainLineTail(long turnId, Long parentTurnId) {
    }

    private final Long id;
    private final TenantId tenantId;
    private final UserId owner;
    private final long version;
    private String title;
    private TitleSource titleSource;
    private List<Long> knowledgeBaseIds;
    private Instant lastAskedAt;
    private MainLineTail tail;

    private Conversation(Long id, TenantId tenantId, UserId owner, long version, String title, TitleSource titleSource,
            List<Long> knowledgeBaseIds, Instant lastAskedAt, MainLineTail tail) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.owner = Objects.requireNonNull(owner);
        this.version = version;
        this.title = Objects.requireNonNull(title);
        this.titleSource = Objects.requireNonNull(titleSource);
        this.knowledgeBaseIds = distinct(knowledgeBaseIds);
        this.lastAskedAt = lastAskedAt;
        this.tail = tail;
    }

    /**
     * @throws IllegalArgumentException 一个知识库都没选
     */
    public static Conversation start(TenantId tenantId, UserId owner, List<Long> knowledgeBaseIds, Instant at) {
        return new Conversation(null, tenantId, owner, 0, NEW_TITLE, TitleSource.NEW, knowledgeBaseIds, at,
                null);
    }

    /**
     * 仓储从数据库恢复聚合时使用。
     *
     * @param tail 主线的末尾，还没有提问时为 null
     */
    public static Conversation restore(long id, TenantId tenantId, UserId owner, long version, String title,
            TitleSource titleSource, List<Long> knowledgeBaseIds, Instant lastAskedAt, MainLineTail tail) {
        return new Conversation(id, tenantId, owner, version, title, titleSource, knowledgeBaseIds, lastAskedAt, tail);
    }

    /** 新建后由仓储回填标识。 */
    public Conversation withId(long newId) {
        return new Conversation(newId, tenantId, owner, version, title, titleSource, knowledgeBaseIds, lastAskedAt,
                tail);
    }

    /** 保存成功后由仓储换上新的版本号。 */
    public Conversation withVersion(long newVersion) {
        return new Conversation(id, tenantId, owner, newVersion, title, titleSource, knowledgeBaseIds, lastAskedAt,
                tail);
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

    /**
     * 检查能不能替换这一轮：只能替换主线最后一轮（D-036：重新生成最后一条、修改最后一个问题）。
     * {@code replacesTurnId} 为 null 表示接着问，总是可以。
     *
     * @throws IllegalArgumentException 要替换的不是主线最后一轮
     */
    public void checkReplaceable(Long replacesTurnId) {
        if (replacesTurnId != null && (tail == null || tail.turnId() != replacesTurnId)) {
            throw new IllegalArgumentException("只能重新生成或修改最后一轮");
        }
    }

    /**
     * 把一轮接到主线末尾，或替换主线最后一轮（替换时新的一轮接在被替换那一轮的上一轮之后），同时记下这次提问。
     *
     * @return 新的一轮接在哪一轮之后；是第一轮时为 null
     * @throws IllegalArgumentException 要替换的不是主线最后一轮
     */
    public Long appendTurn(long turnId, Long replacesTurnId, String question, Instant at) {
        checkReplaceable(replacesTurnId);
        Long parent = replacesTurnId == null ? (tail == null ? null : tail.turnId()) : tail.parentTurnId();
        this.tail = new MainLineTail(turnId, parent);
        asked(question, at);
        return parent;
    }

    /** 记下一次提问：更新最后提问时间（保留期从它算起）；第一次提问时用问题截断当标题。 */
    void asked(String question, Instant at) {
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

    /** 主线的末尾；还没有提问时为 null。 */
    public MainLineTail tail() {
        return tail;
    }

    /** 加载时的版本号，仓储保存时据此判断并发修改。 */
    public long version() {
        return version;
    }
}
