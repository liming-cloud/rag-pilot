package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.knowledge.domain.Chunk;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.shared.TenantId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MybatisDocumentRepository implements DocumentRepository {

    private final DocumentMapper documents;
    private final ChunkMapper chunks;

    MybatisDocumentRepository(DocumentMapper documents, ChunkMapper chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    @Override
    public void save(Document document) {
        var row = new DocumentDO();
        row.setId(document.id());
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
        if (row.getId() == null) {
            documents.insert(row);
            document.assignId(row.getId());
        } else {
            documents.updateById(row);
        }
    }

    @Override
    public void saveChunks(Document document, List<Chunk> values, List<float[]> embeddings) {
        if (values.size() != embeddings.size()) {
            throw new IllegalArgumentException("分块数和向量数不一致");
        }
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
            chunks.insert(row);
        }
    }

    @Override
    public boolean existsInKnowledgeBase(long knowledgeBaseId, String sha256) {
        return documents.exists(new LambdaQueryWrapper<DocumentDO>()
                .eq(DocumentDO::getKnowledgeBaseId, knowledgeBaseId)
                .eq(DocumentDO::getSha256, sha256));
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

    private static Document toDomain(DocumentDO row) {
        return Document.restore(row.getId(), new TenantId(row.getTenantId()), row.getKnowledgeBaseId(),
                row.getFilename(), row.getMediaType(), row.getSha256(), row.getSizeBytes(),
                DocumentStatus.valueOf(row.getStatus()), row.getError(), row.getChunkCount(), row.getCreatedAt(),
                row.getReplacesDocumentId());
    }
}
