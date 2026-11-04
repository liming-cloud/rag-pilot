package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
            citations.computeIfAbsent(row.answerId, key -> new ArrayList<>()).add(new Citation(row.citationIndex,
                    row.chunkId, row.documentId, row.documentName, row.headingPath, null));
        }
        var records = new HashMap<Long, AnswerRecord>();
        for (var row : mapper.records(tenantId.value(), ids)) {
            records.put(row.id, toDomain(row, citations.getOrDefault(row.id, List.of())));
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

    @Override
    public void saveReviews(Collection<AnswerRecord> records) {
        records.stream().collect(Collectors.groupingBy(record -> Arrays.asList(record.tenantId().value(),
                        record.reviewStatus().name(), record.resolutionNote(),
                        record.resolvedBy() == null ? null : record.resolvedBy().value()),
                        LinkedHashMap::new, Collectors.mapping(AnswerRecord::id, Collectors.toList())))
                .forEach((key, ids) -> mapper.updateReviews((Long) key.get(0), ids, (String) key.get(1),
                        (String) key.get(2), (Long) key.get(3)));
    }

    private static AnswerRecord toDomain(AnswerRecordDO row, List<Citation> citations) {
        var reply = new AnswerRecord.Reply(row.answerText, Boolean.TRUE.equals(row.refused),
                row.refusalReason == null ? null : RefusalReason.valueOf(row.refusalReason), citations,
                row.topRelevance, row.rewrittenQuestion);
        // 旧记录没有 knowledge_base_ids（D-047 之前没有记）。
        return AnswerRecord.restore(row.id, new TenantId(row.tenantId), new UserId(row.userId), row.question, reply,
                row.knowledgeBaseIds == null ? List.of() : List.of(row.knowledgeBaseIds),
                row.rating == null ? null : AnswerRecord.Rating.valueOf(row.rating), row.feedbackComment,
                AnswerRecord.ReviewStatus.valueOf(row.reviewStatus), row.resolutionNote,
                row.resolvedBy == null ? null : new UserId(row.resolvedBy));
    }
}
