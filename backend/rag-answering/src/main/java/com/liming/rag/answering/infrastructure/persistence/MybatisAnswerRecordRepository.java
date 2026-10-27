package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MybatisAnswerRecordRepository implements AnswerRecordRepository {

    private final AnswerRecordMapper mapper;

    MybatisAnswerRecordRepository(AnswerRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long record(TenantId tenantId, UserId asker, String question, Answer answer) {
        long id = mapper.insert(tenantId.value(), asker.value(), question, answer.text(), answer.refused(),
                answer.refusalReason() == null ? null : answer.refusalReason().name(), answer.topRelevance());
        for (var citation : answer.citations()) {
            mapper.insertCitation(id, citation.index(), citation.chunkId(), citation.documentId(),
                    citation.documentName(), citation.headingPath() == null ? "" : citation.headingPath());
        }
        return id;
    }

    @Override
    public Optional<AnswerRecord> find(TenantId tenantId, long id) {
        return Optional.ofNullable(mapper.find(tenantId.value(), id)).map(row -> AnswerRecord.restore(
                ((Number) row.get("id")).longValue(),
                new TenantId(((Number) row.get("tenant_id")).longValue()),
                new UserId(((Number) row.get("user_id")).longValue()),
                row.get("rating") == null ? null : AnswerRecord.Rating.valueOf((String) row.get("rating")),
                (String) row.get("feedback_comment")));
    }

    @Override
    public void saveFeedback(AnswerRecord record) {
        mapper.updateFeedback(record.tenantId().value(), record.id(),
                record.rating() == null ? null : record.rating().name(), record.comment());
    }
}
