package com.liming.rag.knowledge.application.ingestion;

import com.liming.rag.knowledge.api.EmbeddingUnavailableException;
import com.liming.rag.knowledge.application.DocumentReaders;
import com.liming.rag.knowledge.application.FileStorage;
import com.liming.rag.knowledge.application.TextEmbedding;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.knowledge.domain.TextChunker;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * 在入库线程池里一次只做一份，不占满对嵌入模型的并发，也不挡住新上传的资料。
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
    private final AtomicBoolean running = new AtomicBoolean();
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

    /** 这一份的结果：做完了、跳过了（下一轮再看或本次运行不再试），还是模型暂时不可用、这一轮停下。 */
    private enum Outcome { DONE, SKIPPED, PAUSED }

    /** 列出要重做的文档，逐份交给入库线程池；上一轮还没做完时不再开新的一轮。 */
    @Scheduled(initialDelay = 1, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void resume() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        var pending = new ConcurrentLinkedQueue<>(stale());
        if (pending.isEmpty()) {
            running.set(false);
            return;
        }
        log.info("event=rechunk_started documents={} version={}", pending.size(), TextChunker.VERSION);
        submitNext(pending);
    }

    /** 一份一个任务，做完一份再把下一份排到队尾：和新上传的资料轮流用入库线程池，不会让上传等到全部重做完。 */
    private void submitNext(Queue<Document> pending) {
        var document = pending.poll();
        if (document == null) {
            log.info("event=rechunk_finished version={}", TextChunker.VERSION);
            running.set(false);
            return;
        }
        try {
            executor.execute(() -> {
                if (rechunkOne(document) == Outcome.PAUSED) {
                    running.set(false);
                } else {
                    submitNext(pending);
                }
            });
        } catch (TaskRejectedException e) {
            // 正在停机：下次启动后接着做。
            running.set(false);
        }
    }

    /**
     * 在当前线程里逐份重做所有分块规则版本旧的文档，模型暂时不可用时停下。
     *
     * @return 重新分块的文档数
     */
    public int rechunkAll() {
        int done = 0;
        for (var document : stale()) {
            var outcome = rechunkOne(document);
            if (outcome == Outcome.PAUSED) {
                break;
            }
            done += outcome == Outcome.DONE ? 1 : 0;
        }
        return done;
    }

    private List<Document> stale() {
        return documents.listStaleChunking(readers.recognizesImages()).stream().filter(document -> !unreadable.contains(document.id())).toList();
    }

    private Outcome rechunkOne(Document document) {
        try {
            return rechunk(document);
        } catch (EmbeddingUnavailableException e) {
            log.warn("event=rechunk_paused document={}", document.id());
            return Outcome.PAUSED;
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
        return Outcome.SKIPPED;
    }

    private Outcome rechunk(Document document) {
        var text = readers.read(document.filename(), storage.read(document.tenantId(), document.sha256()));
        if (text.imageTextPending() && readers.recognizesImages()) {
            // 图片文字识别服务暂时不可用：先不重做，免得白白再向量化一次，下一轮再看（#223）。
            log.warn("event=rechunk_paused document={} reason=ocr", document.id());
            return Outcome.PAUSED;
        }
        var chunks = chunker.chunk(document.filename(), text.content());
        if (chunks.isEmpty()) {
            throw new DocumentParseException("文件里没有可检索的文字", null);
        }
        var vectors = ChunkWriter.vectors(embeddings, chunks);
        document.rechunked(chunks.size());
        transactions.executeWithoutResult(status -> {
            documents.replaceChunks(document, chunks, vectors);
            documents.saveHealthSignals(document, text.images(), text.largestTableRows(), text.imageTextPending());
        });
        log.info("event=document_rechunked document={} chunks={}", document.id(), chunks.size());
        return Outcome.DONE;
    }
}
