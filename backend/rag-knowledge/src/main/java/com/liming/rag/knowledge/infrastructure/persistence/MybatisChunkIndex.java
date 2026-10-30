package com.liming.rag.knowledge.infrastructure.persistence;

import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.application.ChunkIndex;
import com.liming.rag.knowledge.application.Grantee;
import com.liming.rag.shared.TenantId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
class MybatisChunkIndex implements ChunkIndex {

    private final ChunkSearchMapper mapper;

    MybatisChunkIndex(ChunkSearchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Long> nearest(Grantee grantee, List<Long> knowledgeBaseIds, float[] vector, int limit) {
        return mapper.nearest(grantee.tenantId().value(), grantee.userId().value(), grantee.roleIds(),
                knowledgeBaseIds, VectorTypeHandler.format(vector), limit);
    }

    @Override
    public List<Long> keyword(Grantee grantee, List<Long> knowledgeBaseIds, String text, int limit) {
        return mapper.keyword(grantee.tenantId().value(), grantee.userId().value(), grantee.roleIds(),
                knowledgeBaseIds, text, limit);
    }

    @Override
    public List<SearchHit> load(TenantId tenantId, List<Long> chunkIds) {
        return mapper.load(tenantId.value(), chunkIds).stream()
                .map(row -> new SearchHit(
                        ((Number) row.get("chunk_id")).longValue(),
                        ((Number) row.get("document_id")).longValue(),
                        (String) row.get("document_name"),
                        (String) row.get("heading_path"),
                        (String) row.get("content"),
                        0))
                .toList();
    }

    @Override
    public Map<Long, String> accessibleContents(Grantee grantee, List<Long> chunkIds) {
        var contents = new HashMap<Long, String>();
        for (var row : mapper.accessibleContents(grantee.tenantId().value(), grantee.userId().value(),
                grantee.roleIds(), chunkIds)) {
            contents.put(((Number) row.get("chunk_id")).longValue(), (String) row.get("content"));
        }
        return contents;
    }
}
