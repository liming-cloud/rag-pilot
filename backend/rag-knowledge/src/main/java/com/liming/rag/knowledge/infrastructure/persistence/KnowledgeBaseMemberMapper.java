package com.liming.rag.knowledge.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** knowledge_base_member 表；成员关系只有主键，不需要单独的 DO。 */
@Mapper
interface KnowledgeBaseMemberMapper {

    @Insert("""
            INSERT INTO knowledge_base_member (tenant_id, knowledge_base_id, user_id)
            VALUES (#{tenantId}, #{knowledgeBaseId}, #{userId})
            ON CONFLICT DO NOTHING
            """)
    void insert(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("userId") long userId);

    @Delete("""
            DELETE FROM knowledge_base_member
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId} AND user_id = #{userId}
            """)
    void delete(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("userId") long userId);

    @Select("""
            SELECT user_id FROM knowledge_base_member
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId}
            ORDER BY created_at, user_id
            """)
    List<Long> members(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId);

    @Select("""
            SELECT kb.id, kb.tenant_id, kb.name FROM knowledge_base kb
            JOIN knowledge_base_member m ON m.knowledge_base_id = kb.id
            WHERE kb.tenant_id = #{tenantId} AND m.user_id = #{userId}
            ORDER BY kb.id
            """)
    List<KnowledgeBaseDO> accessible(@Param("tenantId") long tenantId, @Param("userId") long userId);
}
