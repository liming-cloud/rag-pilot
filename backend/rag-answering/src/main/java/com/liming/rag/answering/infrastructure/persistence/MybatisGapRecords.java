package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.application.GapRecords;
import com.liming.rag.shared.TenantId;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    private static GapRecord toRecord(Map<String, Object> row) {
        var cited = (String) row.get("cited_documents");
        var createdAt = row.get("created_at") instanceof Timestamp timestamp
                ? OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault())
                : (OffsetDateTime) row.get("created_at");
        return new GapRecord(((Number) row.get("id")).longValue(), ((Number) row.get("user_id")).longValue(),
                (String) row.get("question"), createdAt, (Boolean) row.get("refused"),
                (String) row.get("feedback_comment"),
                cited == null ? List.of() : Arrays.asList(cited.split("\u001F")), (String) row.get("review_status"));
    }
}
