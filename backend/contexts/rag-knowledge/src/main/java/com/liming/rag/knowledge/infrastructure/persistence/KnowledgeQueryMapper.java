package com.liming.rag.knowledge.infrastructure.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 管理端列表的分页查询，SQL 写在 {@code mapper/knowledge/KnowledgeQueryMapper.xml}。 */
@Mapper
interface KnowledgeQueryMapper {

    /** all 为 true 时列本租户全部知识库，否则只列授权给 userId 本人或 roleIds 的。 */
    List<KnowledgeBaseSummaryDO> knowledgeBases(@Param("tenantId") long tenantId, @Param("all") boolean all,
            @Param("userId") Long userId, @Param("roleIds") List<Long> roleIds, @Param("keyword") String keyword,
            @Param("validity") String validity, @Param("today") LocalDate today,
            @Param("warnUntil") LocalDate warnUntil, @Param("offset") long offset, @Param("size") int size);

    long countKnowledgeBases(@Param("tenantId") long tenantId, @Param("all") boolean all,
            @Param("userId") Long userId, @Param("roleIds") List<Long> roleIds, @Param("keyword") String keyword,
            @Param("validity") String validity, @Param("today") LocalDate today,
            @Param("warnUntil") LocalDate warnUntil);

    /** all 为 false 时，体检的重复只在授权给 userId 本人或 roleIds 的库里找（#218）。 */
    List<DocumentRowDO> documents(@Param("tenantId") long tenantId, @Param("all") boolean all,
            @Param("userId") Long userId, @Param("roleIds") List<Long> roleIds,
            @Param("knowledgeBaseId") long knowledgeBaseId, @Param("keyword") String keyword,
            @Param("status") String status, @Param("validity") String validity, @Param("health") String health,
            @Param("today") LocalDate today, @Param("warnUntil") LocalDate warnUntil, @Param("offset") long offset,
            @Param("size") int size);

    long countDocuments(@Param("tenantId") long tenantId, @Param("all") boolean all, @Param("userId") Long userId,
            @Param("roleIds") List<Long> roleIds, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("keyword") String keyword, @Param("status") String status, @Param("validity") String validity,
            @Param("health") String health, @Param("today") LocalDate today, @Param("warnUntil") LocalDate warnUntil);

    /** 这些文档的重复、疑似重复的另一份文档，范围同 {@link #documents}。 */
    List<SimilarDocumentDO> similarDocuments(@Param("tenantId") long tenantId, @Param("all") boolean all,
            @Param("userId") Long userId, @Param("roleIds") List<Long> roleIds,
            @Param("documentIds") Collection<Long> documentIds);

    ExpiryCountDO expirySummary(@Param("tenantId") long tenantId, @Param("all") boolean all,
            @Param("userId") Long userId, @Param("roleIds") List<Long> roleIds, @Param("today") LocalDate today,
            @Param("warnUntil") LocalDate warnUntil);

    List<ExpiringDocumentDO> expiringDocuments(@Param("tenantId") long tenantId, @Param("all") boolean all,
            @Param("userId") Long userId, @Param("roleIds") List<Long> roleIds, @Param("today") LocalDate today,
            @Param("warnUntil") LocalDate warnUntil, @Param("limit") int limit);
}
