package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * knowledge_base_member（授权给账号）：查询和撤销用通用方法；授权（已有时什么也不做）和“能访问哪些库”写在
 * {@code mapper/knowledge/KnowledgeBaseMemberMapper.xml}，后者与检索共用 ChunkSearchMapper 的 accessibleKnowledgeBases。
 */
@Mapper
interface KnowledgeBaseMemberMapper extends BaseMapper<KnowledgeBaseMemberDO> {

    void grant(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("userId") long userId);

    /** 这个库是否授权给了用户本人或其角色，口径同 {@link #accessible}。 */
    boolean isAccessible(@Param("tenantId") long tenantId, @Param("userId") long userId,
            @Param("roleIds") List<Long> roleIds, @Param("knowledgeBaseId") long knowledgeBaseId);

    /** 授权给用户本人或其角色的知识库，过滤条件与检索共用 ChunkSearchMapper 的 accessibleKnowledgeBases。 */
    List<KnowledgeBaseDO> accessible(@Param("tenantId") long tenantId, @Param("userId") long userId,
            @Param("roleIds") List<Long> roleIds);
}
