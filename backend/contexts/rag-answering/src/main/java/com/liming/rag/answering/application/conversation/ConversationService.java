package com.liming.rag.answering.application.conversation;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.application.review.AnswerLogService;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.answering.domain.Conversation;
import com.liming.rag.answering.domain.ConversationRepository;
import com.liming.rag.answering.domain.ConversationTurn;
import com.liming.rag.knowledge.api.DocumentFreshness;
import com.liming.rag.knowledge.api.KnowledgeAccess;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.util.HashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 员工端会话的增删改查（#107、D-036）。只能操作自己的会话，别人的会话按不存在处理。
 *
 * <p>知识库按当前授权处理：新建和改选时只保留员工是成员的库；读取时标出哪些已收回授权；历史回答的引用原文
 * 也按当前授权重新读取，无权时不返回。检索时的权限过滤仍只在 {@link KnowledgeSearch} 里做。
 */
@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final AnswerRecordRepository answers;
    private final KnowledgeAccess access;
    private final KnowledgeSearch search;
    private final AnswerLogService log;
    private final Clock clock;

    ConversationService(ConversationRepository conversations, AnswerRecordRepository answers, KnowledgeAccess access,
            KnowledgeSearch search, AnswerLogService log, Clock clock) {
        this.conversations = conversations;
        this.answers = answers;
        this.access = access;
        this.search = search;
        this.log = log;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ConversationViews.Summary> list(TenantId tenantId, UserId userId) {
        return conversations.listOwned(tenantId, userId).stream().map(ConversationService::summary).toList();
    }

    /**
     * 新建会话。点名无权限的库被忽略；没点名或全被忽略时选中全部已授权的库。
     *
     * @throws AnsweringException 员工还没有任何已授权的知识库
     */
    @Transactional
    public ConversationViews.Summary create(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds) {
        var accessible = access.scope(tenantId, userId, null);
        if (accessible.isEmpty()) {
            throw invalid("你还没有可用的知识库，请联系管理员授权");
        }
        var chosen = access.scope(tenantId, userId, knowledgeBaseIds);
        var saved = conversations.save(Conversation.start(tenantId, userId, chosen.isEmpty() ? accessible : chosen, clock.instant()));
        return summary(saved);
    }

    @Transactional(readOnly = true)
    public ConversationViews.Detail detail(TenantId tenantId, UserId userId, long id) {
        var conversation = owned(tenantId, userId, id);
        var accessible = access.scope(tenantId, userId, null);
        var names = new HashMap<>(access.names(tenantId, conversation.knowledgeBaseIds()));
        var options = conversation.knowledgeBaseIds().stream()
                .filter(names::containsKey)
                .map(kb -> new ConversationViews.KnowledgeBaseOption(kb, names.get(kb), accessible.contains(kb)))
                .toList();
        var turns = conversations.mainLine(id);
        var recorded = recordedAnswers(tenantId, turns);
        var chunkIds = recorded.values().stream().flatMap(answer -> answer.citations().stream())
                .map(Citation::chunkId).collect(Collectors.toSet());
        var excerpts = search.excerpts(tenantId, userId, chunkIds);
        var documents = access.freshness(tenantId, recorded.values().stream()
                .flatMap(answer -> answer.citations().stream()).map(Citation::documentId).collect(Collectors.toSet()));
        var views = turns.stream().map(turn -> new ConversationViews.Turn(turn.id(), turn.question(), turn.status(),
                turn.answerRecordId() == null ? null : withExcerpts(recorded.get(turn.answerRecordId()), excerpts, documents),
                turn.knowledgeBaseIds(), turn.askedAt()))
                .toList();
        return new ConversationViews.Detail(id, conversation.title(), conversation.lastAskedAt(), options, views);
    }

    /**
     * 重命名和改选知识库，两项都可以不传。改选时只保留已授权的库。
     *
     * @throws AnsweringException 会话不存在或不是自己的
     * @throws AnsweringException 标题不合法，或改选后一个已授权的库都不剩
     */
    @Transactional
    public ConversationViews.Summary update(TenantId tenantId, UserId userId, long id, String title,
            List<Long> knowledgeBaseIds) {
        var conversation = owned(tenantId, userId, id);
        try {
            if (title != null) {
                conversation.rename(userId, title);
            }
            if (knowledgeBaseIds != null) {
                // 一个都没点名时交给聚合拒绝，不能当成“全部已授权的库”。
                conversation.selectKnowledgeBases(userId, knowledgeBaseIds.isEmpty() ? List.of()
                        : access.scope(tenantId, userId, knowledgeBaseIds));
            }
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        return summary(save(conversation));
    }

    /** 员工删除会话只影响员工端，回答记录照常保留（D-036）。 */
    @Transactional
    public void delete(TenantId tenantId, UserId userId, long id) {
        owned(tenantId, userId, id);
        conversations.delete(tenantId, id);
    }

    /**
     * 提问前要用到的东西：会话、主线历史（替换时不含被替换的那一轮）、这一轮实际检索的库（所选的库与当前授权取交集）。
     *
     * @throws AnsweringException 会话不存在或不是自己的
     * @throws AnsweringException 要替换的不是主线最后一轮
     */
    @Transactional(readOnly = true)
    TurnContext prepareTurn(TenantId tenantId, UserId userId, long id, @Nullable Long replacesTurnId) {
        var conversation = owned(tenantId, userId, id);
        try {
            conversation.checkReplaceable(replacesTurnId);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        var mainLine = conversations.mainLine(id);
        var history = replacesTurnId == null ? mainLine : mainLine.subList(0, mainLine.size() - 1);
        var recorded = recordedAnswers(tenantId, history);
        var entries = history.stream()
                .map(turn -> new ConversationHistory.Entry(turn.question(),
                        turn.answerRecordId() == null ? null : recorded.get(turn.answerRecordId())))
                .toList();
        var effective = access.scope(tenantId, userId, conversation.knowledgeBaseIds());
        return new TurnContext(conversation.titleSource() == Conversation.TitleSource.NEW, entries, effective);
    }

    /**
     * @param firstTurn 会话还没有提问过，回答后要生成标题
     * @param history 主线历史，按先后排列
     * @param knowledgeBaseIds 这一轮实际检索的库；为空表示所选的库都已收回授权
     */
    record TurnContext(boolean firstTurn, List<ConversationHistory.Entry> history, List<Long> knowledgeBaseIds) {
    }

    /**
     * 要存下的一轮是哪个会话的哪一轮（#145：参数太多时改用对象，照 {@code AskRequest}）。
     *
     * @param knowledgeBaseIds 这一轮实际检索的知识库
     * @param replacesTurnId 重新提问时被替换的那一轮，接着问时为 null
     */
    record NewTurn(TenantId tenantId, UserId userId, long conversationId, long turnId, String question,
            List<Long> knowledgeBaseIds, @Nullable Long replacesTurnId) {
    }

    /**
     * 存下一轮：有回答时先记回答记录，再把这一轮接到主线上，更新最后提问时间（第一次提问时标题用问题截断）。
     * 回答记录和会话在同一个事务里：会话没保存成功时，回答记录也不留（#135）。
     *
     * @param answer 停止时没留下可核对的内容、或出错时为 null
     * @throws AnsweringException 要替换的已不是最后一轮（INVALID），或会话刚被别的提问改过（CONFLICT）
     */
    @Transactional
    ConversationViews.Turn recordTurn(NewTurn newTurn, ConversationTurn.Status status, @Nullable AskResult answer) {
        var tenantId = newTurn.tenantId();
        var userId = newTurn.userId();
        long id = newTurn.conversationId();
        long turnId = newTurn.turnId();
        var question = newTurn.question();
        var knowledgeBaseIds = newTurn.knowledgeBaseIds();
        var replacesTurnId = newTurn.replacesTurnId();
        var conversation = owned(tenantId, userId, id);
        var recorded = answer == null ? null
                : log.recordSearched(tenantId, userId, question, answer, knowledgeBaseIds);
        var now = clock.instant();
        Long parent;
        try {
            parent = conversation.appendTurn(turnId, replacesTurnId, question, now);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        save(conversation);
        var turn = new ConversationTurn(turnId, parent, question.strip(), status,
                recorded == null ? null : recorded.id(), knowledgeBaseIds, now);
        conversations.addTurn(id, turn, replacesTurnId);
        return new ConversationViews.Turn(turnId, turn.question(), status, recorded, knowledgeBaseIds, now);
    }

    /** 预先分配轮次 id：生成开始时就告诉前端，停止生成要用它。 */
    long nextTurnId() {
        return conversations.nextTurnId();
    }

    /** 后台生成标题时，会话被别的操作改过的话重新读一次再试，最多几次。 */
    static final int TITLE_ATTEMPTS = 3;

    /** 模型在后台生成的标题；会话已被删除或员工改过标题时不动。和提问撞上时重新读一次会话再试。 */
    @Transactional
    void applyGeneratedTitle(TenantId tenantId, long id, String title) {
        for (int attempt = 1; attempt <= TITLE_ATTEMPTS; attempt++) {
            var conversation = conversations.find(tenantId, id);
            if (conversation.isEmpty()) {
                return;
            }
            conversation.get().titleGenerated(title);
            try {
                conversations.save(conversation.get());
                return;
            } catch (Conversation.ConcurrentChangeException e) {
                // 读取之后会话被改过，再读一次。
            }
        }
    }

    /** 保存会话；读取之后被别的操作改过时，这次修改作废，让员工刷新后重试。 */
    private Conversation save(Conversation conversation) {
        try {
            return conversations.save(conversation);
        } catch (Conversation.ConcurrentChangeException e) {
            throw new AnsweringException(AnsweringException.Reason.CONFLICT, "这段对话刚在别处更新过，请刷新后重试");
        }
    }

    /** 自己的会话；不存在或不是自己的都按不存在处理。 */
    Conversation owned(TenantId tenantId, UserId userId, long id) {
        var conversation = conversations.find(tenantId, id).orElseThrow(ConversationService::notFound);
        try {
            conversation.requireOwner(userId);
        } catch (Conversation.NotOwnerException e) {
            throw notFound();
        }
        return conversation;
    }

    /** 这些轮次当时的回答，按回答记录 id。 */
    private Map<Long, AskResult> recordedAnswers(TenantId tenantId, List<ConversationTurn> turns) {
        var ids = turns.stream().map(ConversationTurn::answerRecordId).filter(Objects::nonNull).toList();
        var answers = new HashMap<Long, AskResult>();
        this.answers.findAll(tenantId, ids).forEach((id, record) -> answers.put(id, AskResult.of(record)));
        return answers;
    }

    /**
     * 引用原文按当前授权补上，授权已收回或文档已删时为 null；资料的更新时间和状态按文档现在的情况补上，
     * 文档已不在的算被替换（D-026：新版本处理成功后旧版本删除）。
     */
    private static AskResult withExcerpts(AskResult answer, Map<Long, String> excerpts,
            Map<Long, DocumentFreshness> documents) {
        if (answer == null) {
            return null;
        }
        var citations = answer.citations().stream()
                .map(citation -> {
                    var document = documents.get(citation.documentId());
                    return new Citation(citation.index(), citation.chunkId(), citation.documentId(),
                            citation.documentName(), citation.headingPath(), excerpts.get(citation.chunkId()),
                            document == null ? null : document.uploadedAt(), state(document));
                })
                .toList();
        return new AskResult(answer.id(), answer.text(), answer.refused(), answer.refusalReason(), citations,
                answer.dataCitations(), answer.topRelevance(), null, null);
    }

    private static Citation.DocumentState state(DocumentFreshness document) {
        if (document == null) {
            return Citation.DocumentState.REPLACED;
        }
        return document.expired() ? Citation.DocumentState.EXPIRED : Citation.DocumentState.CURRENT;
    }

    private static ConversationViews.Summary summary(Conversation conversation) {
        return new ConversationViews.Summary(conversation.id(), conversation.title(), conversation.knowledgeBaseIds(),
                conversation.lastAskedAt());
    }

    static AnsweringException notFound() {
        return new AnsweringException(AnsweringException.Reason.NOT_FOUND, "会话不存在");
    }

    static AnsweringException invalid(String message) {
        return new AnsweringException(AnsweringException.Reason.INVALID, message);
    }
}
