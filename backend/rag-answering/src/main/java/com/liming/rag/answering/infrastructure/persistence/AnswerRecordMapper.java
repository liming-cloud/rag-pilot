package com.liming.rag.answering.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** answer_record 与 answer_citation 两张表。 */
@Mapper
interface AnswerRecordMapper {

    long insert(@Param("tenantId") long tenantId, @Param("userId") long userId, @Param("question") String question,
            @Param("answerText") String answerText, @Param("refused") boolean refused,
            @Param("refusalReason") String refusalReason, @Param("topRelevance") Double topRelevance,
            @Param("rewrittenQuestion") String rewrittenQuestion);

    void insertCitation(@Param("answerId") long answerId, @Param("index") int index, @Param("chunkId") long chunkId,
            @Param("documentId") long documentId, @Param("documentName") String documentName,
            @Param("headingPath") String headingPath);

    Map<String, Object> find(@Param("tenantId") long tenantId, @Param("id") long id);

    List<Map<String, Object>> answers(@Param("tenantId") long tenantId, @Param("ids") Collection<Long> ids);

    List<Map<String, Object>> citations(@Param("tenantId") long tenantId, @Param("ids") Collection<Long> ids);

    /**
     * filter 为 DISLIKED、REFUSED 或 ALL；status 为 OPEN、RESOLVED 或 null（不筛）；since、keyword 为 null 不筛。
     * 引用的文档名用 \u001F 连接，由调用方拆开。
     */
    List<Map<String, Object>> list(@Param("tenantId") long tenantId, @Param("filter") String filter,
            @Param("status") String status, @Param("since") OffsetDateTime since, @Param("keyword") String keyword,
            @Param("offset") long offset, @Param("size") int size);

    long count(@Param("tenantId") long tenantId, @Param("filter") String filter, @Param("status") String status,
            @Param("since") OffsetDateTime since, @Param("keyword") String keyword);

    /** 统计用的时间条件：created_at 落在 [from, to) 内。评价只算给出了回答的记录。 */
    Map<String, Object> totals(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    List<Map<String, Object>> daily(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("zone") String zone);

    List<Map<String, Object>> refusalReasons(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    List<Map<String, Object>> topRefusedQuestions(@Param("tenantId") long tenantId,
            @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, @Param("limit") int limit);

    List<Map<String, Object>> topCitedDocuments(@Param("tenantId") long tenantId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("limit") int limit);

    void updateReview(@Param("tenantId") long tenantId, @Param("id") long id, @Param("status") String status,
            @Param("note") String note, @Param("resolvedBy") Long resolvedBy);

    void updateFeedback(@Param("tenantId") long tenantId, @Param("id") long id, @Param("rating") String rating,
            @Param("comment") String comment);
}
