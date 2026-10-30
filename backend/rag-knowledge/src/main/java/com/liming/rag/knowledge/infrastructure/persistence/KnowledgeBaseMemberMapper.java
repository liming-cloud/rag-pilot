package com.liming.rag.knowledge.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 知识库授权：knowledge_base_member（授权给账号）和 knowledge_base_role_grant（授权给角色）。 */
@Mapper
interface KnowledgeBaseMemberMapper {

    void insert(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("userId") long userId);

    void delete(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("userId") long userId);

    List<Long> members(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId);

    /** 授权给用户本人或其角色的知识库，过滤条件与检索共用 ChunkSearchMapper 的 accessibleKnowledgeBases。 */
    List<KnowledgeBaseDO> accessible(@Param("tenantId") long tenantId, @Param("userId") long userId,
            @Param("roleIds") List<Long> roleIds);

    void insertRoleGrant(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("roleId") long roleId);

    void deleteRoleGrant(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("roleId") long roleId);

    List<Long> roleGrants(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId);

    void deleteGrantsOfRole(@Param("tenantId") long tenantId, @Param("roleId") long roleId);
}
