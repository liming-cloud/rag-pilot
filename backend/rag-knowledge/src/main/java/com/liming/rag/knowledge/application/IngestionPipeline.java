package com.liming.rag.knowledge.application;

import com.liming.rag.ai.DocumentParseException;
import com.liming.rag.ai.DocumentReaders;
import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.ModelCallException;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import org.springframework.stereotype.Component;

/**
 * 入库管道，按 Spring AI 的 ETL 接口组织：读取（{@link DocumentReaders}）→ 分块（{@link HeadingChunker}）
 * → 向量化并写入（{@link ChunkWriter}）。文件读不出来、模型调用失败时，文档标为失败并记下原因。
 */
@Component
class IngestionPipeline {

    private final DocumentReaders readers;
    private final DocumentRepository documents;
    private final EmbeddingClient embeddings;
    private final HeadingChunker chunker = new HeadingChunker();

    IngestionPipeline(DocumentReaders readers, DocumentRepository documents, EmbeddingClient embeddings) {
        this.readers = readers;
        this.documents = documents;
        this.embeddings = embeddings;
    }

    void run(Document document, byte[] content) {
        try {
            var chunks = chunker.apply(readers.forFile(document.filename(), content).get());
            if (chunks.isEmpty()) {
                document.markFailed("文件里没有可检索的文字");
            } else {
                new ChunkWriter(documents, embeddings, document).accept(chunks);
                document.markReady(chunks.size());
            }
        } catch (DocumentParseException | ModelCallException e) {
            document.markFailed(e.getMessage());
        }
        documents.save(document);
    }
}
