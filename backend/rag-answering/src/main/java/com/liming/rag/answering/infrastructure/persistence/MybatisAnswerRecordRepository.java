package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.api.Citation;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
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
    public long record(TenantId tenantId, UserId asker, String question, Answer answer) {
        long id = mapper.insert(tenantId.value(), asker.value(), question, answer.text(), answer.refused(),
                answer.refusalReason() == null ? null : answer.refusalReason().name(), answer.topRelevance(),
                answer.rewrittenQuestion());
        for (var citation : answer.citations()) {
            mapper.insertCitation(id, citation.index(), citation.chunkId(), citation.documentId(),
                    citation.documentName(), citation.headingPath() == null ? "" : citation.headingPath());
        }
        return id;
    }

    @Override
    public Map<Long, Answer> answers(TenantId tenantId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var citations = new HashMap<Long, List<Citation>>();
        for (var row : mapper.citations(tenantId.value(), ids)) {
            citations.computeIfAbsent(((Number) row.get("answer_id")).longValue(), key -> new ArrayList<>())
                    .add(new Citation(((Number) row.get("citation_index")).intValue(),
                            ((Number) row.get("chunk_id")).longValue(), ((Number) row.get("document_id")).longValue(),
                            (String) row.get("document_name"), (String) row.get("heading_path"), null));
        }
        var answers = new HashMap<Long, Answer>();
        for (var row : mapper.answers(tenantId.value(), ids)) {
            long id = ((Number) row.get("id")).longValue();
            var reason = (String) row.get("refusal_reason");
            var relevance = (Number) row.get("top_relevance");
            answers.put(id, new Answer(id, (String) row.get("answer_text"), Boolean.TRUE.equals(row.get("refused")),
                    reason == null ? null : Answer.RefusalReason.valueOf(reason), citations.getOrDefault(id, List.of()),
                    relevance == null ? null : relevance.doubleValue(), null, null));
        }
        return answers;
    }

    @Override
    public Optional<AnswerRecord> find(TenantId tenantId, long id) {
        return Optional.ofNullable(mapper.find(tenantId.value(), id)).map(row -> AnswerRecord.restore(
                ((Number) row.get("id")).longValue(),
                new TenantId(((Number) row.get("tenant_id")).longValue()),
                new UserId(((Number) row.get("user_id")).longValue()),
                (String) row.get("question"),
                row.get("rating") == null ? null : AnswerRecord.Rating.valueOf((String) row.get("rating")),
                (String) row.get("feedback_comment"),
                AnswerRecord.ReviewStatus.valueOf((String) row.get("review_status")),
                (String) row.get("resolution_note")));
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
}
