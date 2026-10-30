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

    ConversationService(ConversationRepository conversations, AnswerRecordRepository answers, KnowledgeAccess access,
            KnowledgeSearch search) {
        this.conversations = conversations;
        this.answers = answers;
        this.access = access;
        this.search = search;
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
                withExcerpts(recorded.get(turn.answerRecordId()), excerpts), turn.knowledgeBaseIds(), turn.askedAt()))
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
