package com.liming.rag.knowledge.domain;

import com.liming.rag.common.TenantId;
import java.util.List;

/** 文档仓储。分块随文档一起写入，但不作为聚合的一部分加载，避免大文档拖慢读取。 */
public interface DocumentRepository {

    /** 新文档插入并回填标识，已有文档更新状态。 */
    void save(Document document);

    void saveChunks(Document document, List<Chunk> chunks);

    boolean existsInKnowledgeBase(long knowledgeBaseId, String sha256);

    List<Document> list(TenantId tenantId, long knowledgeBaseId);
}
