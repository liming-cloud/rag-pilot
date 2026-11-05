package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** knowledge_base_role_grant（授权给角色）：查询和撤销用通用方法；授权（已有时什么也不做）写在 XML 里。 */
@Mapper
interface KnowledgeBaseRoleGrantMapper extends BaseMapper<KnowledgeBaseRoleGrantDO> {

    void grant(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("roleId") long roleId);
}
