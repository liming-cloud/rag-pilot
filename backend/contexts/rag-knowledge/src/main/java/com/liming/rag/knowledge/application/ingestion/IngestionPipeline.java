package com.liming.rag.knowledge.application.ingestion;

import com.liming.rag.knowledge.application.DocumentReaders;
import com.liming.rag.knowledge.application.TextEmbedding;

import com.liming.rag.knowledge.api.EmbeddingUnavailableException;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 入库管道：读取（{@link DocumentReaders}）→ 分块（{@link HeadingChunker}）
 * → 向量化并写入（{@link ChunkWriter}）。文件读不出来时文档标为失败并记下原因；模型调用失败时另记为可重新处理。
 */
@Component
class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final DocumentReaders readers;
    private final DocumentRepository documents;
    private final TextEmbedding embeddings;
    private final TransactionOperations transactions;
    private final DocumentRemover remover;
    private final Clock clock;
    private final HeadingChunker chunker = new HeadingChunker();

    IngestionPipeline(DocumentReaders readers, DocumentRepository documents, TextEmbedding embeddings,
            TransactionOperations transactions, DocumentRemover remover, Clock clock) {
        this.readers = readers;
        this.documents = documents;
        this.embeddings = embeddings;
        this.transactions = transactions;
        this.remover = remover;
        this.clock = clock;
    }

    /** 在后台线程运行，不在数据库事务里；只有最后写入时开一个短事务。 */
    void run(Document document, byte[] content) {
        try {
            var chunks = chunker.chunk(document.filename(), readers.read(document.filename(), content));
            if (chunks.isEmpty()) {
                failed(document, "文件里没有可检索的文字");
            } else {
                new ChunkWriter(documents, embeddings, transactions, remover, document).write(chunks);
            }
        } catch (DocumentParseException e) {
            failed(document, e.getMessage());
        } catch (EmbeddingUnavailableException e) {
            // 模型暂时不可用不是文件的问题：可以重新处理，第一次失败后自动重试一次（#145 A9）。
            document.markModelUnavailable(e.getMessage(), OffsetDateTime.now(clock));
            documents.saveOutcome(document);
            log.warn("event=document_failed document={} kind=model attempts={} retryAt={}", document.id(),
                    document.attempts(), document.retryAt());
        }
    }

    private void failed(Document document, String reason) {
        document.markFailed(reason);
        documents.saveOutcome(document);
        log.warn("event=document_failed document={} kind=content attempts={}", document.id(), document.attempts());
    }
}
