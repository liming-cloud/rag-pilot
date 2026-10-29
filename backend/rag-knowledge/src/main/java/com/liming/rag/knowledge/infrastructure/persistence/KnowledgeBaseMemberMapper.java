package com.liming.rag.knowledge.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** knowledge_base_member 表；成员关系只有主键，不需要单独的 DO。 */
@Mapper
interface KnowledgeBaseMemberMapper {

    void insert(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("userId") long userId);

    void delete(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("userId") long userId);

    List<Long> members(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId);

    List<KnowledgeBaseDO> accessible(@Param("tenantId") long tenantId, @Param("userId") long userId);
}
