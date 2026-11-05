package com.liming.rag.answering.infrastructure.persistence;

import java.time.OffsetDateTime;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** answer_record 与 answer_citation 两张表。 */
@Mapper
interface AnswerRecordMapper extends BaseMapper<AnswerRecordDO> {

    List<CitationDO> citations(@Param("tenantId") long tenantId, @Param("ids") Collection<Long> ids);

    /**
     * filter 为 DISLIKED、REFUSED 或 ALL；status 为 OPEN、RESOLVED 或 null（不筛）；since、keyword 为 null 不筛。
     * 引用的文档名用 \u001F 连接，由调用方拆开。
     */
    List<AnswerReviewDO> list(@Param("tenantId") long tenantId, @Param("filter") String filter,
            @Param("status") String status, @Param("since") OffsetDateTime since, @Param("keyword") String keyword,
            @Param("offset") long offset, @Param("size") int size);

    long count(@Param("tenantId") long tenantId, @Param("filter") String filter, @Param("status") String status,
            @Param("since") OffsetDateTime since, @Param("keyword") String keyword);

    /** 统计用的时间条件：created_at 落在 [from, to) 内。评价只算给出了回答的记录。 */
    UsageTotalsDO totals(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /** 每条记录在 {@code seconds} 秒内此前同一员工问过的问题，两边都有向量的才列。 */
    List<RepeatPairDO> repeatPairs(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("seconds") long seconds);

    List<DailyUsageDO> daily(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("zone") String zone);

    List<LabelCountDO> refusalReasons(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    List<LabelCountDO> topCitedDocuments(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("limit") int limit);

    List<GapRecordDO> gapRecords(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("limit") int limit);

    long countGapRecords(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    int updateQuestionVectors(@Param("tenantId") long tenantId, @Param("rows") List<QuestionVectorDO> rows);

    List<AnswerReviewDO> between(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("limit") int limit);
}
