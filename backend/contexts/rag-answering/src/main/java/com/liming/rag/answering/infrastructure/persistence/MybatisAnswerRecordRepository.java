package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.answering.domain.DataCitation;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** 单表读写用 MyBatis-Plus 的通用方法；按回答查出处要连表核对租户，写在 XML 里。处理、评价的时间由 {@link Clock} 给出。 */
@Repository
class MybatisAnswerRecordRepository implements AnswerRecordRepository {

    private final AnswerRecordMapper mapper;
    private final CitationMapper citations;
    private final DataCitationMapper dataCitations;
    private final Clock clock;

    MybatisAnswerRecordRepository(AnswerRecordMapper mapper, CitationMapper citations,
            DataCitationMapper dataCitations, Clock clock) {
        this.mapper = mapper;
        this.citations = citations;
        this.dataCitations = dataCitations;
        this.clock = clock;
    }

    @Override
    public AnswerRecord add(AnswerRecord record) {
        var reply = record.reply();
        var row = new AnswerRecordDO();
        row.tenantId = record.tenantId().value();
        row.userId = record.asker().value();
        row.question = record.question();
        row.answerText = reply.text();
        row.refused = reply.refused();
        row.refusalReason = reply.refusalReason() == null ? null : reply.refusalReason().name();
        row.topRelevance = reply.topRelevance();
        row.rewrittenQuestion = reply.rewrittenQuestion();
        row.knowledgeBaseIds = record.knowledgeBaseIds().toArray(Long[]::new);
        mapper.insert(row);
        reply.citations().forEach(citation -> citations.insert(citationRow(row.id, citation)));
        reply.dataCitations().forEach(citation -> dataCitations.insert(dataCitationRow(row.id, citation)));
        return record.withId(row.id);
    }

    private static CitationDO citationRow(Long answerId, Citation citation) {
        var cited = new CitationDO();
        cited.answerId = answerId;
        cited.citationIndex = citation.index();
        cited.chunkId = citation.chunkId();
        cited.documentId = citation.documentId();
        cited.documentName = citation.documentName();
        cited.headingPath = citation.headingPath() == null ? "" : citation.headingPath();
        return cited;
    }

    private static DataCitationDO dataCitationRow(Long answerId, DataCitation citation) {
        var cited = new DataCitationDO();
        cited.answerId = answerId;
        cited.citationIndex = citation.index();
        cited.tool = citation.tool();
        cited.sourceSystem = citation.system();
        cited.dataset = citation.dataset();
        cited.conditions = citation.conditions();
        cited.asOf = citation.asOf();
        cited.queriedAt = citation.queriedAt();
        cited.truncated = citation.truncated();
        return cited;
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
        var cited = new HashMap<Long, List<Citation>>();
        for (var row : mapper.citations(tenantId.value(), ids)) {
            cited.computeIfAbsent(row.answerId, key -> new ArrayList<>()).add(new Citation(row.citationIndex,
                    row.chunkId, row.documentId, row.documentName, row.headingPath, null));
        }
        var data = new HashMap<Long, List<DataCitation>>();
        for (var row : dataCitations.selectList(new LambdaQueryWrapper<DataCitationDO>()
                .in(DataCitationDO::getAnswerId, ids))) {
            data.computeIfAbsent(row.answerId, key -> new ArrayList<>()).add(new DataCitation(row.citationIndex,
                    row.tool, row.sourceSystem, row.dataset, row.conditions, row.asOf, row.queriedAt,
                    Boolean.TRUE.equals(row.truncated)));
        }
        data.values().forEach(list -> list.sort(Comparator.comparingInt(DataCitation::index)));
        var records = new HashMap<Long, AnswerRecord>();
        // 数据出处按回答编号单表查，只挂到下面按租户查出的回答上。
        for (var row : mapper.selectList(new LambdaQueryWrapper<AnswerRecordDO>()
                .eq(AnswerRecordDO::getTenantId, tenantId.value())
                .in(AnswerRecordDO::getId, ids))) {
            records.put(row.id, toDomain(row, cited.getOrDefault(row.id, List.of()),
                    data.getOrDefault(row.id, List.of())));
        }
        return records;
    }

    @Override
    public void saveFeedback(AnswerRecord record) {
        var rated = record.rating() != null;
        mapper.update(new LambdaUpdateWrapper<AnswerRecordDO>()
                .set(AnswerRecordDO::getRating, rated ? record.rating().name() : null)
                .set(AnswerRecordDO::getFeedbackComment, record.comment())
                .set(AnswerRecordDO::getRatedAt, rated ? OffsetDateTime.now(clock) : null)
                .eq(AnswerRecordDO::getTenantId, record.tenantId().value())
                .eq(AnswerRecordDO::getId, record.id()));
    }

    @Override
    public void saveReview(AnswerRecord record) {
        saveReviews(List.of(record));
    }

    @Override
    public void saveReviews(Collection<AnswerRecord> records) {
        records.stream().collect(Collectors.groupingBy(record -> Arrays.asList(record.tenantId().value(),
                        record.reviewStatus().name(), record.resolutionNote(),
                        record.resolvedBy() == null ? null : record.resolvedBy().value()),
                        LinkedHashMap::new, Collectors.mapping(AnswerRecord::id, Collectors.toList())))
                .forEach((key, ids) -> updateReviews((Long) key.get(0), ids, (String) key.get(1),
                        (String) key.get(2), (Long) key.get(3)));
    }

    /** 一批记录改成同样的处理状态、说明和处理人；标为已处理时记下处理时间，重新打开时清掉。 */
    private void updateReviews(long tenantId, List<Long> ids, String status, @Nullable String note,
            @Nullable Long resolvedBy) {
        var resolved = AnswerRecord.ReviewStatus.RESOLVED.name().equals(status);
        mapper.update(new LambdaUpdateWrapper<AnswerRecordDO>()
                .set(AnswerRecordDO::getReviewStatus, status)
                .set(AnswerRecordDO::getResolutionNote, note)
                .set(AnswerRecordDO::getResolvedBy, resolvedBy)
                .set(AnswerRecordDO::getResolvedAt, resolved ? OffsetDateTime.now(clock) : null)
                .eq(AnswerRecordDO::getTenantId, tenantId)
                .in(AnswerRecordDO::getId, ids));
    }

    private static AnswerRecord toDomain(AnswerRecordDO row, List<Citation> citations,
            List<DataCitation> dataCitations) {
        var reply = new AnswerRecord.Reply(row.answerText, Boolean.TRUE.equals(row.refused),
                row.refusalReason == null ? null : RefusalReason.valueOf(row.refusalReason), citations, dataCitations,
                row.topRelevance, row.rewrittenQuestion);
        // 旧记录没有 knowledge_base_ids（D-047 之前没有记）。
        return AnswerRecord.restore(row.id, new TenantId(row.tenantId), new UserId(row.userId), row.question, reply,
                row.knowledgeBaseIds == null ? List.of() : List.of(row.knowledgeBaseIds),
                row.rating == null ? null : AnswerRecord.Rating.valueOf(row.rating), row.feedbackComment,
                AnswerRecord.ReviewStatus.valueOf(row.reviewStatus), row.resolutionNote,
                row.resolvedBy == null ? null : new UserId(row.resolvedBy));
    }
}
