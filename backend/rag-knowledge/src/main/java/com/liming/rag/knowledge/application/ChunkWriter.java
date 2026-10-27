package com.liming.rag.knowledge.application;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import java.util.List;
import org.springframework.ai.document.DocumentWriter;

/**
 * 入库管道的写入步骤：给分块向量化，连同租户、知识库一起写进 chunk 表。每份文档用一个实例。
 */
final class ChunkWriter implements DocumentWriter {

    private final DocumentRepository documents;
    private final EmbeddingClient embeddings;
    private final Document target;

    ChunkWriter(DocumentRepository documents, EmbeddingClient embeddings, Document target) {
        this.documents = documents;
        this.embeddings = embeddings;
        this.target = target;
    }

    @Override
    public void accept(List<org.springframework.ai.document.Document> chunks) {
        var domainChunks = chunks.stream().map(HeadingChunker::toChunk).toList();
        // 向量化时带上章节位置，短分块也能借标题获得上下文。
        var vectors = embeddings.embed(domainChunks.stream()
                .map(chunk -> chunk.headingPath() + "\n" + chunk.content())
                .toList());
        documents.saveChunks(target, domainChunks, vectors);
    }
}
