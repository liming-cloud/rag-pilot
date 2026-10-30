package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.application.AnswerReview;
import com.liming.rag.answering.application.AnswerReviews;
import com.liming.rag.answering.application.Paged;
import com.liming.rag.shared.TenantId;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
class MybatisAnswerReviews implements AnswerReviews {

    private final AnswerRecordMapper mapper;

    MybatisAnswerReviews(AnswerRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Paged<AnswerReview> page(TenantId tenantId, AnswerReview.Query query) {
        var status = query.status() == AnswerReview.Status.ALL ? null : query.status().name();
        var page = query.page();
        var rows = mapper.list(tenantId.value(), query.filter().name(), status, query.since(), query.keyword(),
                page.offset(), page.size());
        long total = mapper.count(tenantId.value(), query.filter().name(), status, query.since(), query.keyword());
        return new Paged<>(rows.stream().map(MybatisAnswerReviews::toReview).toList(), total, page.page(),
                page.size());
    }

    private static AnswerReview toReview(Map<String, Object> row) {
        var cited = (String) row.get("cited_documents");
        return new AnswerReview(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("user_id")).longValue(),
                null,
                (String) row.get("question"),
                (String) row.get("answer_text"),
                (Boolean) row.get("refused"),
                (String) row.get("refusal_reason"),
                row.get("top_relevance") == null ? null : ((Number) row.get("top_relevance")).doubleValue(),
                (String) row.get("rating"),
                (String) row.get("feedback_comment"),
                timestamp(row.get("created_at")),
                cited == null ? List.of() : Arrays.asList(cited.split("\u001F")),
                (String) row.get("rewritten_question"),
                (String) row.get("review_status"),
                (String) row.get("resolution_note"),
                timestamp(row.get("resolved_at")));
    }

    /** 以 Map 读取时 timestamptz 是 java.sql.Timestamp，换成带时区的时间。 */
    private static OffsetDateTime timestamp(Object value) {
        return value instanceof Timestamp timestamp
                ? OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault())
                : (OffsetDateTime) value;
    }
}
