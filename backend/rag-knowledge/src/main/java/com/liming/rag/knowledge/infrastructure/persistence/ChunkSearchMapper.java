package com.liming.rag.knowledge.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 混合检索的三条 SQL。每条都限定租户和知识库，这是检索链路里唯一的权限过滤点。
 */
@Mapper
interface ChunkSearchMapper {

    @Select("""
            <script>
            SELECT id FROM chunk
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id IN <foreach collection="knowledgeBaseIds" item="kb" open="(" separator="," close=")">#{kb}</foreach>
              AND embedding IS NOT NULL
            ORDER BY embedding &lt;=&gt; CAST(#{vector} AS vector)
            LIMIT #{limit}
            </script>
            """)
    List<Long> nearest(@Param("tenantId") long tenantId, @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds,
            @Param("vector") String vector, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT id FROM chunk
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id IN <foreach collection="knowledgeBaseIds" item="kb" open="(" separator="," close=")">#{kb}</foreach>
              AND content ||| #{text}
            ORDER BY pdb.score(id) DESC
            LIMIT #{limit}
            </script>
            """)
    List<Long> keyword(@Param("tenantId") long tenantId, @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds,
            @Param("text") String text, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT c.id AS chunk_id, c.document_id, d.filename AS document_name, c.heading_path, c.content
            FROM chunk c JOIN document d ON d.id = c.document_id
            WHERE c.tenant_id = #{tenantId}
              AND c.id IN <foreach collection="chunkIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String, Object>> load(@Param("tenantId") long tenantId, @Param("chunkIds") List<Long> chunkIds);
}
