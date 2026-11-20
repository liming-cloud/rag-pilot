package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 文档仓储。分块随文档一起写入，但不作为聚合的一部分加载，避免大文档拖慢读取。 */
public interface DocumentRepository {

    /**
     * 新文档插入并回填标识；已有文档按版本号整份更新。
     *
     * @throws Document.ConcurrentChangeException 文档在读取之后被别的操作改过
     */
    void save(Document document);

    /**
     * 后台写处理结果：只更新处理状态（状态、失败原因、分块数、重新处理），不动有效期等管理员可改的内容。
     *
     * @throws Document.ConcurrentChangeException 文档已被删除或不在解析中
     */
    void saveOutcome(Document document);

    /** 分块与向量一一对应，按顺序写入；文档记下当前的分块规则版本（{@link TextChunker#VERSION}）。 */
    void saveChunks(Document document, List<Chunk> chunks, List<float[]> embeddings);

    /**
     * 重新分块（#225）：旧分块的正文留档后删除，写入新分块，更新分块数和分块规则版本。在一个事务里调用，
     * 之前旧分块照常参与检索。
     *
     * @throws Document.ConcurrentChangeException 文档在读取之后被删除、修改过，或已不是可用状态
     */
    void replaceChunks(Document document, List<Chunk> chunks, List<float[]> embeddings);

    /**
     * 所有租户里要重新分块的可用文档，按标识顺序：分块规则版本比当前旧的（#225），以及要求时图片文字待识别的（#223）。
     */
    List<Document> listStaleChunking(boolean withPendingImageText);

    /**
     * 资料体检的信号（#218）：记下读取时得到的图片数、最大表格行数和图片文字是否待识别（#223），从已写入的分块算出字数、
     * 空白、乱码和标题层级，并重新比较这份文档和租户里其他文档的分块正文（疑似重复）。在写分块的同一个事务里调用。
     */
    void saveHealthSignals(Document document, int images, int largestTableRows, boolean imageTextPending);

    /** 同一个库里同一份文件的文档（库内按内容唯一）。 */
    Optional<Document> findInKnowledgeBase(long knowledgeBaseId, String sha256);

    List<Document> list(TenantId tenantId, long knowledgeBaseId);

    Optional<Document> find(TenantId tenantId, long id);

    /** 删除文档，分块随之级联删除。返回是否真的删了。 */
    boolean delete(TenantId tenantId, long id);

    /** 租户里还有没有文档用到这份原始文件（同一文件可能在不同知识库各传一次）。 */
    boolean existsBySha256(TenantId tenantId, String sha256);

    /** 所有租户里还在解析中的文档，应用启动时据此恢复未完成的入库。 */
    List<Document> listParsing();

    /** 所有租户里到了自动重试时间（{@code retryAt <= now}）的失败文档。 */
    List<Document> listDueForRetry(OffsetDateTime now);
}
