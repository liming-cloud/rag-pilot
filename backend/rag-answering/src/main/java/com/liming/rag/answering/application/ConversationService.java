package com.liming.rag.answering.application;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.api.Citation;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.answering.domain.Conversation;
import com.liming.rag.answering.domain.ConversationRepository;
import com.liming.rag.answering.domain.ConversationTurn;
import com.liming.rag.knowledge.api.KnowledgeAccess;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.HashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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

    ConversationService(ConversationRepository conversations, AnswerRecordRepository answers, KnowledgeAccess access,
            KnowledgeSearch search, AnswerLogService log) {
        this.conversations = conversations;
        this.answers = answers;
        this.access = access;
        this.search = search;
        this.log = log;
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
        var accessible = accessibleIds(tenantId, userId);
        if (accessible.isEmpty()) {
            throw invalid("你还没有可用的知识库，请联系管理员授权");
        }
        var chosen = knowledgeBaseIds == null ? List.<Long>of()
                : knowledgeBaseIds.stream().filter(accessible::contains).toList();
        var saved = conversations.save(Conversation.start(tenantId, userId, chosen.isEmpty() ? accessible : chosen));
        return summary(saved);
    }

    @Transactional(readOnly = true)
    public ConversationViews.Detail detail(TenantId tenantId, UserId userId, long id) {
        var conversation = owned(tenantId, userId, id);
        var accessible = accessibleIds(tenantId, userId);
        var names = new HashMap<>(access.names(tenantId, conversation.knowledgeBaseIds()));
        var options = conversation.knowledgeBaseIds().stream()
                .filter(names::containsKey)
                .map(kb -> new ConversationViews.KnowledgeBaseOption(kb, names.get(kb), accessible.contains(kb)))
                .toList();
        var turns = conversations.mainLine(id);
        var recorded = answers.answers(tenantId, turns.stream().map(ConversationTurn::answerRecordId)
                .filter(Objects::nonNull).toList());
        var chunkIds = recorded.values().stream().flatMap(answer -> answer.citations().stream())
                .map(Citation::chunkId).collect(Collectors.toSet());
        var excerpts = search.excerpts(tenantId, userId, chunkIds);
        var views = turns.stream().map(turn -> new ConversationViews.Turn(turn.id(), turn.question(), turn.status(),
                turn.answerRecordId() == null ? null : withExcerpts(recorded.get(turn.answerRecordId()), excerpts),
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
                var accessible = accessibleIds(tenantId, userId);
                conversation.selectKnowledgeBases(userId, knowledgeBaseIds.stream().filter(accessible::contains).toList());
            }
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        return summary(conversations.save(conversation));
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
    TurnContext prepareTurn(TenantId tenantId, UserId userId, long id, Long replacesTurnId) {
        var conversation = owned(tenantId, userId, id);
        var mainLine = conversations.mainLine(id);
        if (replacesTurnId != null && (mainLine.isEmpty() || mainLine.getLast().id() != replacesTurnId)) {
            throw invalid("只能重新生成或修改最后一轮");
        }
        var history = replacesTurnId == null ? mainLine : mainLine.subList(0, mainLine.size() - 1);
        var recorded = answers.answers(tenantId, history.stream().map(ConversationTurn::answerRecordId)
                .filter(Objects::nonNull).toList());
        var entries = history.stream()
                .map(turn -> new ConversationHistory.Entry(turn.question(),
                        turn.answerRecordId() == null ? null : recorded.get(turn.answerRecordId())))
                .toList();
        var accessible = accessibleIds(tenantId, userId);
        var effective = conversation.knowledgeBaseIds().stream().filter(accessible::contains).toList();
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
     * 存下一轮：有回答时先记回答记录，再把这一轮接到主线上，更新最后提问时间（第一次提问时标题用问题截断）。
     *
     * @param answer 停止时没留下可核对的内容、或出错时为 null
     */
    @Transactional
    ConversationViews.Turn recordTurn(TenantId tenantId, UserId userId, long id, long turnId, String question,
            ConversationTurn.Status status, Answer answer, List<Long> knowledgeBaseIds, Long replacesTurnId) {
        var conversation = owned(tenantId, userId, id);
        var recorded = answer == null ? null : log.record(tenantId, userId, question, answer);
        var now = Instant.now();
        var turn = new ConversationTurn(turnId, null, question.strip(), status, recorded == null ? null : recorded.id(),
                knowledgeBaseIds, now);
        try {
            conversations.addTurn(id, turn, replacesTurnId);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        conversation.asked(question, now);
        conversations.save(conversation);
        return new ConversationViews.Turn(turnId, turn.question(), status, recorded, knowledgeBaseIds, now);
    }

    /** 预先分配轮次 id：生成开始时就告诉前端，停止生成要用它。 */
    long nextTurnId() {
        return conversations.nextTurnId();
    }

    /** 模型在后台生成的标题；会话已被删除或员工改过标题时不动。 */
    @Transactional
    void applyGeneratedTitle(TenantId tenantId, long id, String title) {
        conversations.find(tenantId, id).ifPresent(conversation -> {
            conversation.titleGenerated(title);
            conversations.save(conversation);
        });
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

    List<Long> accessibleIds(TenantId tenantId, UserId userId) {
        return access.accessible(tenantId, userId).stream().map(KnowledgeBaseRef::id).toList();
    }

    /** 引用原文按当前授权补上；授权已收回或文档已删时为 null。 */
    private static Answer withExcerpts(Answer answer, Map<Long, String> excerpts) {
        if (answer == null) {
            return null;
        }
        var citations = answer.citations().stream()
                .map(citation -> new Citation(citation.index(), citation.chunkId(), citation.documentId(),
                        citation.documentName(), citation.headingPath(), excerpts.get(citation.chunkId())))
                .toList();
        return new Answer(answer.id(), answer.text(), answer.refused(), answer.refusalReason(), citations,
                answer.topRelevance(), null, null);
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
