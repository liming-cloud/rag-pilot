package com.liming.rag.answering.infrastructure.persistence;

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
import org.springframework.stereotype.Repository;

@Repository
class MybatisConversationRepository implements ConversationRepository {

    private final ConversationMapper mapper;

    MybatisConversationRepository(ConversationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Conversation save(Conversation conversation) {
        var ids = conversation.knowledgeBaseIds().toArray(Long[]::new);
        var lastAskedAt = time(conversation.lastAskedAt());
        if (conversation.id() == null) {
            long id = mapper.insert(conversation.tenantId().value(), conversation.owner().value(), conversation.title(),
                    conversation.titleSource().name(), ids, lastAskedAt);
            return conversation.withId(id);
        }
        var tail = conversation.tail();
        int updated = mapper.update(conversation.tenantId().value(), conversation.id(), conversation.version(),
                conversation.title(), conversation.titleSource().name(), ids, lastAskedAt,
                tail == null ? null : tail.turnId());
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
        mapper.delete(tenantId.value(), id);
    }

    @Override
    public int deleteInactiveSince(Instant before) {
        return mapper.deleteInactiveSince(time(before));
    }

    @Override
    public long nextTurnId() {
        return mapper.nextTurnId();
    }

    @Override
    public void addTurn(long conversationId, ConversationTurn turn, Long replacesTurnId) {
        if (replacesTurnId != null) {
            mapper.markSuperseded(conversationId, replacesTurnId);
        }
        mapper.insertTurn(turn.id(), conversationId, turn.parentTurnId(), turn.question(), turn.status().name(),
                turn.answerRecordId(), turn.knowledgeBaseIds().toArray(Long[]::new), time(turn.askedAt()));
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
