package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.application.review.AnswerReview;
import com.liming.rag.answering.application.review.AnswerReviews;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.shared.Paged;
import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
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

    @Override
    public List<AnswerReview> between(TenantId tenantId, OffsetDateTime from, OffsetDateTime to, int limit) {
        return mapper.between(tenantId.value(), from, to, limit).stream().map(MybatisAnswerReviews::toReview).toList();
    }

    private static AnswerReview toReview(AnswerReviewDO row) {
        return new AnswerReview(row.id, row.userId, null, row.question, row.answerText, row.refused,
                row.refusalReason, row.topRelevance, row.rating, row.feedbackComment, row.createdAt,
                row.citedDocuments == null ? List.of() : Arrays.asList(row.citedDocuments.split("\u001F")),
                row.rewrittenQuestion, AnswerRecord.ReviewStatus.valueOf(row.reviewStatus), row.resolutionNote,
                row.resolvedAt);
    }
}
