package com.liming.rag.answering.infrastructure.persistence;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** answer_record 与 answer_citation 两张表。 */
@Mapper
interface AnswerRecordMapper {

    @Select("""
            INSERT INTO answer_record (tenant_id, user_id, question, answer_text, refused, refusal_reason, top_relevance)
            VALUES (#{tenantId}, #{userId}, #{question}, #{answerText}, #{refused}, #{refusalReason}, #{topRelevance})
            RETURNING id
            """)
    long insert(@Param("tenantId") long tenantId, @Param("userId") long userId, @Param("question") String question,
            @Param("answerText") String answerText, @Param("refused") boolean refused,
            @Param("refusalReason") String refusalReason, @Param("topRelevance") Double topRelevance);

    @Insert("""
            INSERT INTO answer_citation (answer_id, citation_index, chunk_id, document_id, document_name, heading_path)
            VALUES (#{answerId}, #{index}, #{chunkId}, #{documentId}, #{documentName}, #{headingPath})
            """)
    void insertCitation(@Param("answerId") long answerId, @Param("index") int index, @Param("chunkId") long chunkId,
            @Param("documentId") long documentId, @Param("documentName") String documentName,
            @Param("headingPath") String headingPath);

    @Select("""
            SELECT id, tenant_id, user_id, rating, feedback_comment FROM answer_record
            WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    Map<String, Object> find(@Param("tenantId") long tenantId, @Param("id") long id);

    @Update("""
            UPDATE answer_record
            SET rating = #{rating}, feedback_comment = #{comment},
                rated_at = CASE WHEN CAST(#{rating} AS varchar) IS NULL THEN NULL ELSE now() END
            WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    void updateFeedback(@Param("tenantId") long tenantId, @Param("id") long id, @Param("rating") String rating,
            @Param("comment") String comment);
}
