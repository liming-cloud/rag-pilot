package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.TenantId;
import java.util.List;
import java.util.Optional;

/** 文档仓储。分块随文档一起写入，但不作为聚合的一部分加载，避免大文档拖慢读取。 */
public interface DocumentRepository {

    /** 新文档插入并回填标识，已有文档更新状态。 */
    void save(Document document);

    /** 分块与向量一一对应，按顺序写入。 */
    void saveChunks(Document document, List<Chunk> chunks, List<float[]> embeddings);

    boolean existsInKnowledgeBase(long knowledgeBaseId, String sha256);

    List<Document> list(TenantId tenantId, long knowledgeBaseId);

    Optional<Document> find(TenantId tenantId, long id);

    /** 删除文档，分块随之级联删除。返回是否真的删了。 */
    boolean delete(TenantId tenantId, long id);

    /** 租户里还有没有文档用到这份原始文件（同一文件可能在不同知识库各传一次）。 */
    boolean existsBySha256(TenantId tenantId, String sha256);

    /** 所有租户里还在解析中的文档，应用启动时据此恢复未完成的入库。 */
    List<Document> listParsing();
}
