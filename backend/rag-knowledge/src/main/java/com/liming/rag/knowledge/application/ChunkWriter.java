package com.liming.rag.knowledge.application;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import java.util.List;
import org.springframework.ai.document.DocumentWriter;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 入库管道的写入步骤：给分块向量化，连同租户、知识库一起写进 chunk 表，并把文档标为可用。每份文档用一个实例。
 *
 * <p>向量化在事务外进行；写分块和改状态在同一个事务里，中途失败不会留下“有分块却仍在解析中”的文档。
 */
final class ChunkWriter implements DocumentWriter {

    private final DocumentRepository documents;
    private final EmbeddingClient embeddings;
    private final TransactionOperations transactions;
    private final Document target;

    ChunkWriter(DocumentRepository documents, EmbeddingClient embeddings, TransactionOperations transactions,
            Document target) {
        this.documents = documents;
        this.embeddings = embeddings;
        this.transactions = transactions;
        this.target = target;
    }

    @Override
    public void accept(List<org.springframework.ai.document.Document> chunks) {
        var domainChunks = chunks.stream().map(HeadingChunker::toChunk).toList();
        // 向量化时带上章节位置，短分块也能借标题获得上下文。
        var vectors = embeddings.embed(domainChunks.stream()
                .map(chunk -> chunk.headingPath() + "\n" + chunk.content())
                .toList());
        transactions.executeWithoutResult(status -> {
            documents.saveChunks(target, domainChunks, vectors);
            target.markReady(domainChunks.size());
            documents.save(target);
        });
    }
}
