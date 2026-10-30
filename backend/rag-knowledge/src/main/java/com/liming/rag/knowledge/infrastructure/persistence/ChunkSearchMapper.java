package com.liming.rag.knowledge.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 混合检索的三条 SQL，写在 {@code mapper/knowledge/ChunkSearchMapper.xml}。两条召回 SQL 都限定租户，并只在用户是成员的知识库里查，这是检索链路里唯一的权限过滤点；
 * 第三条按召回得到的分块主键取详情，范围已经由前两条限定。第四条给历史回答取引用原文，同样按成员过滤。
 */
@Mapper
interface ChunkSearchMapper {

    List<Long> nearest(@Param("tenantId") long tenantId, @Param("userId") long userId,
            @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds,
            @Param("vector") String vector, @Param("limit") int limit);

    List<Long> keyword(@Param("tenantId") long tenantId, @Param("userId") long userId,
            @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds,
            @Param("text") String text, @Param("limit") int limit);

    List<Map<String, Object>> load(@Param("tenantId") long tenantId, @Param("chunkIds") List<Long> chunkIds);

    /** 按分块取原文，和两条召回 SQL 一样只在用户是成员的知识库里查。 */
    List<Map<String, Object>> accessibleContents(@Param("tenantId") long tenantId, @Param("userId") long userId,
            @Param("chunkIds") List<Long> chunkIds);
}
