package com.liming.rag.answering.infrastructure.persistence;

import java.util.List;
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
            INSERT INTO answer_record (tenant_id, user_id, question, answer_text, refused, refusal_reason, top_relevance,
                                       rewritten_question)
            VALUES (#{tenantId}, #{userId}, #{question}, #{answerText}, #{refused}, #{refusalReason}, #{topRelevance},
                    #{rewrittenQuestion})
            RETURNING id
            """)
    long insert(@Param("tenantId") long tenantId, @Param("userId") long userId, @Param("question") String question,
            @Param("answerText") String answerText, @Param("refused") boolean refused,
            @Param("refusalReason") String refusalReason, @Param("topRelevance") Double topRelevance,
            @Param("rewrittenQuestion") String rewrittenQuestion);

    @Insert("""
            INSERT INTO answer_citation (answer_id, citation_index, chunk_id, document_id, document_name, heading_path)
            VALUES (#{answerId}, #{index}, #{chunkId}, #{documentId}, #{documentName}, #{headingPath})
            """)
    void insertCitation(@Param("answerId") long answerId, @Param("index") int index, @Param("chunkId") long chunkId,
            @Param("documentId") long documentId, @Param("documentName") String documentName,
            @Param("headingPath") String headingPath);

    @Select("""
            SELECT id, tenant_id, user_id, question, rating, feedback_comment, review_status, resolution_note
            FROM answer_record
            WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    Map<String, Object> find(@Param("tenantId") long tenantId, @Param("id") long id);

    /** filter 为 DISLIKED、REFUSED 或 ALL；引用的文档名用 \u001F 连接，由调用方拆开。 */
    @Select("""
            <script>
            SELECT r.id, r.user_id, r.question, r.answer_text, r.refused, r.refusal_reason, r.top_relevance,
                   r.rating, r.feedback_comment, r.created_at, r.rewritten_question, r.review_status,
                   r.resolution_note, r.resolved_at,
                   (SELECT string_agg(DISTINCT c.document_name, chr(31)) FROM answer_citation c
                     WHERE c.answer_id = r.id) AS cited_documents
            FROM answer_record r
            WHERE r.tenant_id = #{tenantId}
            <if test="filter == 'DISLIKED'">AND r.rating = 'DOWN'</if>
            <if test="filter == 'REFUSED'">AND r.refused</if>
            <if test="!includeResolved">AND r.review_status = 'OPEN'</if>
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> list(@Param("tenantId") long tenantId, @Param("filter") String filter,
            @Param("includeResolved") boolean includeResolved, @Param("limit") int limit);

    @Update("""
            UPDATE answer_record
            SET review_status = #{status}, resolution_note = #{note}, resolved_by = #{resolvedBy},
                resolved_at = CASE WHEN #{status} = 'RESOLVED' THEN now() ELSE NULL END
            WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    void updateReview(@Param("tenantId") long tenantId, @Param("id") long id, @Param("status") String status,
            @Param("note") String note, @Param("resolvedBy") Long resolvedBy);

    @Update("""
            UPDATE answer_record
            SET rating = #{rating}, feedback_comment = #{comment},
                rated_at = CASE WHEN CAST(#{rating} AS varchar) IS NULL THEN NULL ELSE now() END
            WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    void updateFeedback(@Param("tenantId") long tenantId, @Param("id") long id, @Param("rating") String rating,
            @Param("comment") String comment);
}
