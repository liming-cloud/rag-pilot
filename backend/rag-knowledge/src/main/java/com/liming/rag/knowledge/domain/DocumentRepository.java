package com.liming.rag.knowledge.domain;

import com.liming.rag.common.TenantId;
import java.util.List;

/** 文档仓储。分块随文档一起写入，但不作为聚合的一部分加载，避免大文档拖慢读取。 */
public interface DocumentRepository {

    /** 新文档插入并回填标识，已有文档更新状态。 */
    void save(Document document);

    /** 分块与向量一一对应，按顺序写入。 */
    void saveChunks(Document document, List<Chunk> chunks, List<float[]> embeddings);

    boolean existsInKnowledgeBase(long knowledgeBaseId, String sha256);

    List<Document> list(TenantId tenantId, long knowledgeBaseId);
}
