package com.liming.rag.answering.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** conversation 与 conversation_turn 两张表。知识库 id 存成 bigint[]。 */
@Mapper
interface ConversationMapper {

    long insert(@Param("tenantId") long tenantId, @Param("userId") long userId, @Param("title") String title,
            @Param("titleSource") String titleSource, @Param("knowledgeBaseIds") Long[] knowledgeBaseIds,
            @Param("lastAskedAt") OffsetDateTime lastAskedAt);

    void update(@Param("tenantId") long tenantId, @Param("id") long id, @Param("title") String title,
            @Param("titleSource") String titleSource, @Param("knowledgeBaseIds") Long[] knowledgeBaseIds,
            @Param("lastAskedAt") OffsetDateTime lastAskedAt);

    Map<String, Object> find(@Param("tenantId") long tenantId, @Param("id") long id);

    List<Map<String, Object>> listOwned(@Param("tenantId") long tenantId, @Param("userId") long userId);

    void delete(@Param("tenantId") long tenantId, @Param("id") long id);

    int deleteInactiveSince(@Param("before") OffsetDateTime before);

    long nextTurnId();

    Long lastMainLineTurnId(@Param("conversationId") long conversationId);

    /** 被替换的那一轮接在哪一轮之后；不是这个会话主线上的轮次时返回空。 */
    Map<String, Object> mainLineTurn(@Param("conversationId") long conversationId, @Param("turnId") long turnId);

    void markSuperseded(@Param("conversationId") long conversationId, @Param("turnId") long turnId);

    void insertTurn(@Param("id") long id, @Param("conversationId") long conversationId,
            @Param("parentTurnId") Long parentTurnId, @Param("question") String question,
            @Param("status") String status, @Param("answerRecordId") Long answerRecordId,
            @Param("knowledgeBaseIds") Long[] knowledgeBaseIds, @Param("askedAt") OffsetDateTime askedAt);

    List<Map<String, Object>> mainLine(@Param("conversationId") long conversationId);
}
