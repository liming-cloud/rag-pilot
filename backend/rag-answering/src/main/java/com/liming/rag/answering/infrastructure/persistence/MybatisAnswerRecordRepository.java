package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MybatisAnswerRecordRepository implements AnswerRecordRepository {

    private final AnswerRecordMapper mapper;

    MybatisAnswerRecordRepository(AnswerRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AnswerRecord add(AnswerRecord record) {
        var reply = record.reply();
        long id = mapper.insert(record.tenantId().value(), record.asker().value(), record.question(), reply.text(),
                reply.refused(), reply.refusalReason() == null ? null : reply.refusalReason().name(),
                reply.topRelevance(), reply.rewrittenQuestion(), record.knowledgeBaseIds().toArray(Long[]::new));
        for (var citation : reply.citations()) {
            mapper.insertCitation(id, citation.index(), citation.chunkId(), citation.documentId(),
                    citation.documentName(), citation.headingPath() == null ? "" : citation.headingPath());
        }
        return record.withId(id);
    }

    @Override
    public Optional<AnswerRecord> find(TenantId tenantId, long id) {
        return Optional.ofNullable(findAll(tenantId, List.of(id)).get(id));
    }

    @Override
    public Map<Long, AnswerRecord> findAll(TenantId tenantId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var citations = new HashMap<Long, List<Citation>>();
        for (var row : mapper.citations(tenantId.value(), ids)) {
            citations.computeIfAbsent(number(row, "answer_id"), key -> new ArrayList<>())
                    .add(new Citation(((Number) row.get("citation_index")).intValue(), number(row, "chunk_id"),
                            number(row, "document_id"), (String) row.get("document_name"),
                            (String) row.get("heading_path"), null));
        }
        var records = new HashMap<Long, AnswerRecord>();
        for (var row : mapper.records(tenantId.value(), ids)) {
            long id = number(row, "id");
            records.put(id, toDomain(row, citations.getOrDefault(id, List.of())));
        }
        return records;
    }

    @Override
    public void saveFeedback(AnswerRecord record) {
        mapper.updateFeedback(record.tenantId().value(), record.id(),
                record.rating() == null ? null : record.rating().name(), record.comment());
    }

    @Override
    public void saveReview(AnswerRecord record) {
        mapper.updateReview(record.tenantId().value(), record.id(), record.reviewStatus().name(),
                record.resolutionNote(), record.resolvedBy() == null ? null : record.resolvedBy().value());
    }

    private static AnswerRecord toDomain(Map<String, Object> row, List<Citation> citations) {
        var reason = (String) row.get("refusal_reason");
        var relevance = (Number) row.get("top_relevance");
        var rating = (String) row.get("rating");
        var resolvedBy = (Number) row.get("resolved_by");
        var reply = new AnswerRecord.Reply((String) row.get("answer_text"), Boolean.TRUE.equals(row.get("refused")),
                reason == null ? null : RefusalReason.valueOf(reason), citations,
                relevance == null ? null : relevance.doubleValue(), (String) row.get("rewritten_question"));
        return AnswerRecord.restore(number(row, "id"), new TenantId(number(row, "tenant_id")),
                new UserId(number(row, "user_id")), (String) row.get("question"), reply,
                longs(row.get("knowledge_base_ids")), rating == null ? null : AnswerRecord.Rating.valueOf(rating),
                (String) row.get("feedback_comment"),
                AnswerRecord.ReviewStatus.valueOf((String) row.get("review_status")),
                (String) row.get("resolution_note"), resolvedBy == null ? null : new UserId(resolvedBy.longValue()));
    }

    /** bigint[] 列：旧记录为空（D-047 之前没有记）；驱动可能给出 Long[]，也可能给出 {@link Array}。 */
    private static List<Long> longs(Object value) {
        if (value == null) {
            return List.of();
        }
        Object[] array;
        try {
            array = value instanceof Array sqlArray ? (Object[]) sqlArray.getArray() : (Object[]) value;
        } catch (SQLException e) {
            throw new IllegalStateException("读取知识库 id 数组失败", e);
        }
        return Arrays.stream(array).map(item -> ((Number) item).longValue()).toList();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
