package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.application.insight.GapRecords;
import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
class MybatisGapRecords implements GapRecords {

    private final AnswerRecordMapper mapper;

    MybatisGapRecords(AnswerRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<GapRecord> list(TenantId tenantId, OffsetDateTime from, OffsetDateTime to, int limit) {
        return mapper.gapRecords(tenantId.value(), from, to, limit).stream().map(MybatisGapRecords::toRecord).toList();
    }

    @Override
    public long count(TenantId tenantId, OffsetDateTime from, OffsetDateTime to) {
        return mapper.countGapRecords(tenantId.value(), from, to);
    }

    private static GapRecord toRecord(GapRecordDO row) {
        return new GapRecord(row.id, row.userId, row.question, row.createdAt, row.refused, row.feedbackComment,
                row.citedDocuments == null ? List.of() : Arrays.asList(row.citedDocuments.split("\u001F")),
                row.reviewStatus, row.knowledgeBaseIds == null ? List.of() : List.of(row.knowledgeBaseIds));
    }
}
