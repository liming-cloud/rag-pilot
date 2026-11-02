package com.liming.rag.knowledge.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 混合检索的 SQL，写在 {@code mapper/knowledge/ChunkSearchMapper.xml}。两条召回 SQL 都限定租户，并只在授权给用户本人或其角色
 * 的知识库里查（片段 {@code accessibleKnowledgeBases}），这是检索链路里唯一的权限过滤点，同时只查 {@code today} 当天有效的文档
 * （片段 {@code currentDocuments}）；{@code load} 按召回得到的分块主键
 * 取详情，范围已经由前两条限定；{@code accessibleContents} 给历史回答取引用原文，同样按授权过滤。
 */
@Mapper
interface ChunkSearchMapper {

    List<Long> nearest(@Param("tenantId") long tenantId, @Param("userId") long userId,
            @Param("roleIds") List<Long> roleIds, @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds,
            @Param("today") LocalDate today, @Param("vector") String vector, @Param("limit") int limit);

    List<Long> keyword(@Param("tenantId") long tenantId, @Param("userId") long userId,
            @Param("roleIds") List<Long> roleIds, @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds,
            @Param("today") LocalDate today, @Param("text") String text, @Param("limit") int limit);

    List<Map<String, Object>> load(@Param("tenantId") long tenantId, @Param("chunkIds") List<Long> chunkIds);

    /** 按分块取原文，和两条召回 SQL 一样只在授权给用户本人或其角色的知识库里查。 */
    List<Map<String, Object>> accessibleContents(@Param("tenantId") long tenantId, @Param("userId") long userId,
            @Param("roleIds") List<Long> roleIds, @Param("chunkIds") List<Long> chunkIds);
}
