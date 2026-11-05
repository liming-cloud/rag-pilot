package com.liming.rag.knowledge.infrastructure.persistence;

import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.application.ChunkIndex;
import com.liming.rag.knowledge.application.Grantee;
import com.liming.rag.shared.TenantId;
import java.util.HashMap;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class MybatisChunkIndex implements ChunkIndex {

    private final ChunkSearchMapper mapper;

    MybatisChunkIndex(ChunkSearchMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 向量召回。HNSW 索引先扫出一批再按授权、有效期过滤，可访问的资料只占一小部分时会剩得很少（#145）：在这条查询的事务里
     * 打开 pgvector 的迭代扫描，不够时继续往下扫；只对这条查询生效，不改库级设置（库级设置在恢复备份后会丢）。
     */
    @Override
    @Transactional(readOnly = true)
    public List<Long> nearest(Grantee grantee, List<Long> knowledgeBaseIds, LocalDate today, float[] vector,
            int limit) {
        mapper.enableIterativeScan();
        return mapper.nearest(grantee.tenantId().value(), grantee.userId().value(), grantee.roleIds(),
                knowledgeBaseIds, today, VectorTypeHandler.format(vector), limit);
    }

    @Override
    public List<Long> keyword(Grantee grantee, List<Long> knowledgeBaseIds, LocalDate today, String text,
            int limit) {
        return mapper.keyword(grantee.tenantId().value(), grantee.userId().value(), grantee.roleIds(),
                knowledgeBaseIds, today, text, limit);
    }

    @Override
    public List<SearchHit> load(TenantId tenantId, List<Long> chunkIds) {
        return mapper.load(tenantId.value(), chunkIds).stream()
                .map(row -> new SearchHit(row.chunkId, row.documentId, row.documentName, row.headingPath,
                        row.content, 0))
                .toList();
    }

    @Override
    public Map<Long, String> accessibleContents(Grantee grantee, List<Long> chunkIds) {
        var contents = new HashMap<Long, String>();
        for (var row : mapper.accessibleContents(grantee.tenantId().value(), grantee.userId().value(),
                grantee.roleIds(), chunkIds)) {
            contents.put(row.chunkId, row.content);
        }
        return contents;
    }
}
