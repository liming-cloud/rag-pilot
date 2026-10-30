package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.domain.Conversation;
import com.liming.rag.answering.domain.ConversationRepository;
import com.liming.rag.answering.domain.ConversationTurn;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
        mapper.update(conversation.tenantId().value(), conversation.id(), conversation.title(),
                conversation.titleSource().name(), ids, lastAskedAt);
        return conversation;
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
        Long parent = mapper.lastMainLineTurnId(conversationId);
        if (replacesTurnId != null) {
            // 只能替换主线最后一轮（D-036：重新生成最后一条、修改最后一个问题）。
            var replaced = mapper.mainLineTurn(conversationId, replacesTurnId);
            if (replaced == null || !replacesTurnId.equals(parent)) {
                throw new IllegalArgumentException("只能重新生成或修改最后一轮");
            }
            parent = replaced.get("parent_turn_id") == null ? null : ((Number) replaced.get("parent_turn_id")).longValue();
            mapper.markSuperseded(conversationId, replacesTurnId);
        }
        mapper.insertTurn(turn.id(), conversationId, parent, turn.question(), turn.status().name(),
                turn.answerRecordId(), turn.knowledgeBaseIds().toArray(Long[]::new), time(turn.askedAt()));
    }

    @Override
    public List<ConversationTurn> mainLine(long conversationId) {
        return mapper.mainLine(conversationId).stream()
                .map(row -> new ConversationTurn(
                        ((Number) row.get("id")).longValue(),
                        row.get("parent_turn_id") == null ? null : ((Number) row.get("parent_turn_id")).longValue(),
                        (String) row.get("question"),
                        ConversationTurn.Status.valueOf((String) row.get("status")),
                        row.get("answer_record_id") == null ? null : ((Number) row.get("answer_record_id")).longValue(),
                        longs(row.get("knowledge_base_ids")),
                        instant(row.get("asked_at"))))
                .toList();
    }

    private static Conversation toDomain(Map<String, Object> row) {
        return Conversation.restore(((Number) row.get("id")).longValue(),
                new TenantId(((Number) row.get("tenant_id")).longValue()),
                new UserId(((Number) row.get("user_id")).longValue()),
                (String) row.get("title"),
                Conversation.TitleSource.valueOf((String) row.get("title_source")),
                longs(row.get("knowledge_base_ids")),
                instant(row.get("last_asked_at")));
    }

    /** bigint[] 列：驱动可能直接给出 Long[]，也可能给出 {@link Array}。 */
    private static List<Long> longs(Object value) {
        Object[] array;
        try {
            array = value instanceof Array sqlArray ? (Object[]) sqlArray.getArray() : (Object[]) value;
        } catch (SQLException e) {
            throw new IllegalStateException("读取知识库 id 数组失败", e);
        }
        return Arrays.stream(array).map(item -> ((Number) item).longValue()).toList();
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case OffsetDateTime time -> time.toInstant();
            case java.sql.Timestamp time -> time.toInstant();
            default -> throw new IllegalStateException("不认识的时间类型：" + value.getClass());
        };
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
