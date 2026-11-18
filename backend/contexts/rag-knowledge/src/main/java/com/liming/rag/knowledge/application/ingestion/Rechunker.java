package com.liming.rag.knowledge.application.ingestion;

import com.liming.rag.knowledge.api.EmbeddingUnavailableException;
import com.liming.rag.knowledge.application.DocumentReaders;
import com.liming.rag.knowledge.application.FileStorage;
import com.liming.rag.knowledge.application.TextEmbedding;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.knowledge.domain.TextChunker;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 升级后自动重新分块（#225、D-070）：分块规则（{@link TextChunker#VERSION}）比文档入库时新的，后台逐份重新读取原文件、
 * 分块、向量化，再在一个事务里替换。替换之前旧分块照常参与检索；旧分块的正文留档，历史回答的引用原文仍能查到。
 *
 * <p>进度就是每份文档记下的分块规则版本：停机、模型暂时不可用时停下，下一轮从没做完的接着做。每 10 分钟检查一次，
 * 在入库线程池里一次只做一份，不占满对嵌入模型的并发。
 */
@Component
public class Rechunker {

    private static final Logger log = LoggerFactory.getLogger(Rechunker.class);

    private final DocumentRepository documents;
    private final FileStorage storage;
    private final DocumentReaders readers;
    private final HeadingChunker chunker;
    private final TextEmbedding embeddings;
    private final TransactionOperations transactions;
    private final Executor executor;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    /** 读不出来、处理出错的文档：本次运行期间不再重试，免得每一轮都报同样的错，旧分块继续可用；重启后再试一次。 */
    private final Set<Long> unreadable = ConcurrentHashMap.newKeySet();

    Rechunker(DocumentRepository documents, FileStorage storage, DocumentReaders readers, HeadingChunker chunker,
            TextEmbedding embeddings, TransactionOperations transactions,
            @Qualifier(IngestionExecutorConfiguration.BEAN_NAME) Executor executor) {
        this.documents = documents;
        this.storage = storage;
        this.readers = readers;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.transactions = transactions;
        this.executor = executor;
    }

    /** 把重新分块交给入库线程池；上一轮还没做完时不再提交。 */
    @Scheduled(initialDelay = 1, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void resume() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    rechunkAll();
                } finally {
                    scheduled.set(false);
                }
            });
        } catch (TaskRejectedException e) {
            // 正在停机：下次启动后接着做。
            scheduled.set(false);
        }
    }

    /**
     * 逐份重新分块所有分块规则版本旧的文档，模型暂时不可用时停下等下一轮。同一时间只有一轮在做。
     *
     * @return 这一轮重新分块的文档数
     */
    public synchronized int rechunkAll() {
        var stale = documents.listStaleChunking().stream().filter(document -> !unreadable.contains(document.id()))
                .toList();
        if (stale.isEmpty()) {
            return 0;
        }
        log.info("event=rechunk_started documents={} version={}", stale.size(), TextChunker.VERSION);
        int done = 0;
        for (var document : stale) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                rechunk(document);
                done++;
            } catch (EmbeddingUnavailableException e) {
                log.warn("event=rechunk_paused document={} done={} remaining={}", document.id(), done,
                        stale.size() - done);
                return done;
            } catch (Document.ConcurrentChangeException e) {
                // 处理期间被删除、修改或替换：不写回，还需要的话下一轮再做。
                log.info("event=rechunk_skipped document={}", document.id());
            } catch (DocumentParseException e) {
                unreadable.add(document.id());
                log.warn("event=rechunk_failed document={} kind=content", document.id());
            } catch (RuntimeException e) {
                unreadable.add(document.id());
                log.error("event=rechunk_failed document={} kind=error", document.id(), e);
            }
        }
        log.info("event=rechunk_finished documents={} version={}", done, TextChunker.VERSION);
        return done;
    }

    private void rechunk(Document document) {
        var text = readers.read(document.filename(), storage.read(document.tenantId(), document.sha256()));
        var chunks = chunker.chunk(document.filename(), text.content());
        if (chunks.isEmpty()) {
            throw new DocumentParseException("文件里没有可检索的文字", null);
        }
        var vectors = ChunkWriter.vectors(embeddings, chunks);
        document.rechunked(chunks.size());
        transactions.executeWithoutResult(status -> {
            documents.replaceChunks(document, chunks, vectors);
            documents.saveHealthSignals(document, text.images(), text.largestTableRows());
        });
        log.info("event=document_rechunked document={} chunks={}", document.id(), chunks.size());
    }
}
