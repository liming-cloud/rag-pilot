package com.liming.rag.knowledge.infrastructure.persistence;

import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.application.ChunkIndex;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
class MybatisChunkIndex implements ChunkIndex {

    private final ChunkSearchMapper mapper;

    MybatisChunkIndex(ChunkSearchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Long> nearest(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, float[] vector,
            int limit) {
        return mapper.nearest(tenantId.value(), userId.value(), knowledgeBaseIds, VectorTypeHandler.format(vector), limit);
    }

    @Override
    public List<Long> keyword(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String text,
            int limit) {
        return mapper.keyword(tenantId.value(), userId.value(), knowledgeBaseIds, text, limit);
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
}
