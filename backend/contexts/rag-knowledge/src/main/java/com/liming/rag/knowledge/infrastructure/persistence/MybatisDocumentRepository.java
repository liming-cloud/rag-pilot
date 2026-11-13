package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.liming.rag.knowledge.domain.Chunk;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MybatisDocumentRepository implements DocumentRepository {

    /** 分块每批写入的条数。 */
    static final int CHUNK_BATCH = 500;

    private final DocumentMapper documents;
    private final ChunkMapper chunks;

    MybatisDocumentRepository(DocumentMapper documents, ChunkMapper chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    @Override
    public void save(Document document) {
        if (document.id() == null) {
            var row = new DocumentDO();
            row.setTenantId(document.tenantId().value());
            row.setKnowledgeBaseId(document.knowledgeBaseId());
            row.setFilename(document.filename());
            row.setMediaType(document.mediaType());
            row.setSha256(document.sha256());
            row.setSizeBytes(document.sizeBytes());
            row.setStatus(document.status().name());
            row.setError(document.error());
            row.setChunkCount(document.chunkCount());
            row.setReplacesDocumentId(document.replacesDocumentId());
            row.setEffectiveFrom(document.validity().effectiveFrom());
            row.setExpiresOn(document.validity().expiresOn());
            row.setReprocessable(document.reprocessable());
            row.setRetryAt(document.retryAt());
            row.setAttempts(document.attempts());
            documents.insert(row);
            document.assignId(row.getId());
            return;
        }
        // 乐观锁：版本号不是读取时的那个，说明文档已被别的操作改过，不更新（0 行）。
        update(document, outcome(document)
                .set(DocumentDO::getEffectiveFrom, document.validity().effectiveFrom())
                .set(DocumentDO::getExpiresOn, document.validity().expiresOn())
                .eq(DocumentDO::getVersion, document.version()));
    }

    @Override
    public void saveOutcome(Document document) {
        // 处理要几分钟，期间管理员可能改了有效期：只写处理结果，条件是文档还在解析中（没被删除、没被别人处理完）。
        update(document, outcome(document).eq(DocumentDO::getStatus, DocumentStatus.PARSING.name()));
    }

    /** 处理状态相关的列，版本号加一。 */
    private static LambdaUpdateWrapper<DocumentDO> outcome(Document document) {
        return new LambdaUpdateWrapper<DocumentDO>()
                .set(DocumentDO::getStatus, document.status().name())
                .set(DocumentDO::getError, document.error())
                .set(DocumentDO::getChunkCount, document.chunkCount())
                .set(DocumentDO::getReprocessable, document.reprocessable())
                .set(DocumentDO::getRetryAt, document.retryAt())
                .set(DocumentDO::getAttempts, document.attempts())
                .setIncrBy(DocumentDO::getVersion, 1)
                .eq(DocumentDO::getTenantId, document.tenantId().value())
                .eq(DocumentDO::getId, document.id());
    }

    private void update(Document document, LambdaUpdateWrapper<DocumentDO> wrapper) {
        if (documents.update(wrapper) == 0) {
            throw new Document.ConcurrentChangeException();
        }
        document.versionSaved();
    }

    @Override
    public void saveChunks(Document document, List<Chunk> values, List<float[]> embeddings) {
        if (values.size() != embeddings.size()) {
            throw new IllegalArgumentException("分块数和向量数不一致");
        }
        var rows = new ArrayList<ChunkDO>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var chunk = values.get(i);
            var row = new ChunkDO();
            row.setTenantId(document.tenantId().value());
            row.setKnowledgeBaseId(document.knowledgeBaseId());
            row.setDocumentId(document.id());
            row.setOrdinal(chunk.ordinal());
            row.setHeadingPath(chunk.headingPath());
            row.setContent(chunk.content());
            row.setTokenCount(chunk.tokenCount());
            row.setEmbedding(embeddings.get(i));
            rows.add(row);
        }
        // 多行 INSERT，按批写，免得一条语句的参数太多（PostgreSQL 一条最多 65535 个）。
        for (int from = 0; from < rows.size(); from += CHUNK_BATCH) {
            chunks.insertAll(rows.subList(from, Math.min(rows.size(), from + CHUNK_BATCH)));
        }
    }

    @Override
    public void saveHealthSignals(Document document, int images, int largestTableRows) {
        long tenantId = document.tenantId().value();
        documents.refreshSignals(tenantId, document.id(), images, largestTableRows);
        documents.deleteOverlaps(tenantId, document.id());
        documents.insertOverlaps(tenantId, document.id());
    }

    @Override
    public Optional<Document> findInKnowledgeBase(long knowledgeBaseId, String sha256) {
        return Optional.ofNullable(documents.selectOne(new LambdaQueryWrapper<DocumentDO>()
                        .eq(DocumentDO::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(DocumentDO::getSha256, sha256)))
                .map(MybatisDocumentRepository::toDomain);
    }

    @Override
    public List<Document> list(TenantId tenantId, long knowledgeBaseId) {
        return documents.selectList(new LambdaQueryWrapper<DocumentDO>()
                        .eq(DocumentDO::getTenantId, tenantId.value())
                        .eq(DocumentDO::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByDesc(DocumentDO::getId))
                .stream().map(MybatisDocumentRepository::toDomain).toList();
    }

    @Override
    public Optional<Document> find(TenantId tenantId, long id) {
        return Optional.ofNullable(documents.selectOne(new LambdaQueryWrapper<DocumentDO>()
                        .eq(DocumentDO::getTenantId, tenantId.value())
                        .eq(DocumentDO::getId, id)))
                .map(MybatisDocumentRepository::toDomain);
    }

    @Override
    public boolean delete(TenantId tenantId, long id) {
        return documents.delete(new LambdaQueryWrapper<DocumentDO>()
                .eq(DocumentDO::getTenantId, tenantId.value())
                .eq(DocumentDO::getId, id)) > 0;
    }

    @Override
    public boolean existsBySha256(TenantId tenantId, String sha256) {
        return documents.exists(new LambdaQueryWrapper<DocumentDO>()
                .eq(DocumentDO::getTenantId, tenantId.value())
                .eq(DocumentDO::getSha256, sha256));
    }

    @Override
    public List<Document> listParsing() {
        return documents.selectList(new LambdaQueryWrapper<DocumentDO>()
                        .eq(DocumentDO::getStatus, DocumentStatus.PARSING.name())
                        .orderByAsc(DocumentDO::getId))
                .stream().map(MybatisDocumentRepository::toDomain).toList();
    }

    @Override
    public List<Document> listDueForRetry(OffsetDateTime now) {
        return documents.selectList(new LambdaQueryWrapper<DocumentDO>()
                        .eq(DocumentDO::getStatus, DocumentStatus.FAILED.name())
                        .le(DocumentDO::getRetryAt, now)
                        .orderByAsc(DocumentDO::getId))
                .stream().map(MybatisDocumentRepository::toDomain).toList();
    }

    static Document toDomain(DocumentDO row) {
        return Document.restore(row.getId(), row.getVersion(), new TenantId(row.getTenantId()), row.getKnowledgeBaseId(),
                row.getFilename(), row.getMediaType(), row.getSha256(), row.getSizeBytes(),
                DocumentStatus.valueOf(row.getStatus()), row.getError(), row.getChunkCount(), row.getCreatedAt(),
                row.getReplacesDocumentId(), new Validity(row.getEffectiveFrom(), row.getExpiresOn()),
                new Document.Retry(Boolean.TRUE.equals(row.getReprocessable()), row.getRetryAt(), row.getAttempts()));
    }
}
