package com.liming.rag.answering.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** conversation 与 conversation_turn 两张表。知识库 id 存成 bigint[]。 */
@Mapper
interface ConversationMapper {

    long insert(@Param("tenantId") long tenantId, @Param("userId") long userId, @Param("title") String title,
            @Param("titleSource") String titleSource, @Param("knowledgeBaseIds") Long[] knowledgeBaseIds,
            @Param("lastAskedAt") OffsetDateTime lastAskedAt);

    /** 按版本号更新，版本号加一；返回更新的行数，版本已变时为 0。 */
    int update(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
            @Param("title") String title, @Param("titleSource") String titleSource,
            @Param("knowledgeBaseIds") Long[] knowledgeBaseIds, @Param("lastAskedAt") OffsetDateTime lastAskedAt,
            @Param("lastTurnId") Long lastTurnId);

    ConversationDO find(@Param("tenantId") long tenantId, @Param("id") long id);

    List<ConversationDO> listOwned(@Param("tenantId") long tenantId, @Param("userId") long userId);

    void delete(@Param("tenantId") long tenantId, @Param("id") long id);

    int deleteInactiveSince(@Param("before") OffsetDateTime before);

    long nextTurnId();

    void markSuperseded(@Param("conversationId") long conversationId, @Param("turnId") long turnId);

    void insertTurn(@Param("id") long id, @Param("conversationId") long conversationId,
            @Param("parentTurnId") Long parentTurnId, @Param("question") String question,
            @Param("status") String status, @Param("answerRecordId") Long answerRecordId,
            @Param("knowledgeBaseIds") Long[] knowledgeBaseIds, @Param("askedAt") OffsetDateTime askedAt);

    List<ConversationTurnDO> mainLine(@Param("conversationId") long conversationId);
}
