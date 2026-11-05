package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.liming.rag.answering.domain.Conversation;
import com.liming.rag.answering.domain.ConversationRepository;
import com.liming.rag.answering.domain.ConversationTurn;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** 单表写入用 MyBatis-Plus 的通用方法；带上一轮的查询、按主线递归查轮次写在 XML 里。 */
@Repository
class MybatisConversationRepository implements ConversationRepository {

    private static final String ARRAY = "typeHandler=org.apache.ibatis.type.ArrayTypeHandler";

    private final ConversationMapper mapper;
    private final ConversationTurnMapper turns;

    MybatisConversationRepository(ConversationMapper mapper, ConversationTurnMapper turns) {
        this.mapper = mapper;
        this.turns = turns;
    }

    @Override
    public Conversation save(Conversation conversation) {
        var ids = conversation.knowledgeBaseIds().toArray(Long[]::new);
        var lastAskedAt = time(conversation.lastAskedAt());
        if (conversation.id() == null) {
            var row = new ConversationDO();
            row.tenantId = conversation.tenantId().value();
            row.userId = conversation.owner().value();
            row.title = conversation.title();
            row.titleSource = conversation.titleSource().name();
            row.knowledgeBaseIds = ids;
            row.lastAskedAt = lastAskedAt;
            mapper.insert(row);
            return conversation.withId(row.id);
        }
        var tail = conversation.tail();
        // 乐观锁：版本号不是加载时的那个，说明会话已被别的操作改过，不更新（0 行）。
        int updated = mapper.update(new LambdaUpdateWrapper<ConversationDO>()
                .set(ConversationDO::getTitle, conversation.title())
                .set(ConversationDO::getTitleSource, conversation.titleSource().name())
                .set(ConversationDO::getKnowledgeBaseIds, ids, ARRAY)
                .set(ConversationDO::getLastAskedAt, lastAskedAt)
                .set(ConversationDO::getLastTurnId, tail == null ? null : tail.turnId())
                .set(ConversationDO::getVersion, conversation.version() + 1)
                .eq(ConversationDO::getTenantId, conversation.tenantId().value())
                .eq(ConversationDO::getId, conversation.id())
                .eq(ConversationDO::getVersion, conversation.version()));
        if (updated == 0) {
            throw new Conversation.ConcurrentChangeException();
        }
        return conversation.withVersion(conversation.version() + 1);
    }

    @Override
    public Optional<Conversation> find(TenantId tenantId, long id) {
        return Optional.ofNullable(mapper.find(tenantId.value(), id)).map(MybatisConversationRepository::toDomain);
    }

    @Override
    public List<Conversation> listOwned(TenantId tenantId, UserId owner) {
        return mapper.listOwned(tenantId.value(), owner.value()).stream()
                .map(MybatisConversationRepository::toDomain).toList();
    }

    @Override
    public void delete(TenantId tenantId, long id) {
        mapper.delete(new LambdaQueryWrapper<ConversationDO>()
                .eq(ConversationDO::getTenantId, tenantId.value())
                .eq(ConversationDO::getId, id));
    }

    @Override
    public int deleteInactiveSince(Instant before) {
        return mapper.delete(new LambdaQueryWrapper<ConversationDO>().lt(ConversationDO::getLastAskedAt, time(before)));
    }

    @Override
    public long nextTurnId() {
        return mapper.nextTurnId();
    }

    @Override
    public void addTurn(long conversationId, ConversationTurn turn, @Nullable Long replacesTurnId) {
        if (replacesTurnId != null) {
            turns.update(new LambdaUpdateWrapper<ConversationTurnDO>()
                    .set(ConversationTurnDO::getSuperseded, true)
                    .eq(ConversationTurnDO::getConversationId, conversationId)
                    .eq(ConversationTurnDO::getId, replacesTurnId));
        }
        var row = new ConversationTurnDO();
        row.id = turn.id();
        row.conversationId = conversationId;
        row.parentTurnId = turn.parentTurnId();
        row.question = turn.question();
        row.status = turn.status().name();
        row.answerRecordId = turn.answerRecordId();
        row.knowledgeBaseIds = turn.knowledgeBaseIds().toArray(Long[]::new);
        row.askedAt = time(turn.askedAt());
        turns.insert(row);
    }

    @Override
    public List<ConversationTurn> mainLine(long conversationId) {
        return mapper.mainLine(conversationId).stream()
                .map(row -> new ConversationTurn(row.id, row.parentTurnId, row.question,
                        ConversationTurn.Status.valueOf(row.status), row.answerRecordId,
                        List.of(row.knowledgeBaseIds), row.askedAt.toInstant()))
                .toList();
    }

    private static Conversation toDomain(ConversationDO row) {
        return Conversation.restore(row.id, new TenantId(row.tenantId), new UserId(row.userId), row.version,
                row.title, Conversation.TitleSource.valueOf(row.titleSource), List.of(row.knowledgeBaseIds),
                row.lastAskedAt.toInstant(), row.lastTurnId == null ? null
                        : new Conversation.MainLineTail(row.lastTurnId, row.lastTurnParentId));
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
