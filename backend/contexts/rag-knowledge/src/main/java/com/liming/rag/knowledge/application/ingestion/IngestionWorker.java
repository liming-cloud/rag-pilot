package com.liming.rag.knowledge.application.ingestion;

import com.liming.rag.knowledge.application.FileStorage;
import com.liming.rag.knowledge.application.KnowledgeProperties;

import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.shared.TenantId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 后台入库：上传事务提交后把文档交给 {@link IngestionPipeline}。
 *
 * <p>同时处理的文档数由 {@code rag.knowledge.ingestion.concurrency} 限制，避免大批上传时打满嵌入模型的限流。
 * 文档表里的“解析中”状态就是持久化的任务记录：应用启动时把它们重新排队，所以重启不会丢任务。
 */
@Component
public class IngestionWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final DocumentRepository documents;
    private final FileStorage storage;
    private final IngestionPipeline pipeline;
    private final ExecutorService executor;
    /** 正在排队或处理的文档，避免启动恢复和上传事件把同一份文档提交两次。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private final Clock clock;

    IngestionWorker(DocumentRepository documents, FileStorage storage, IngestionPipeline pipeline,
            KnowledgeProperties properties, Clock clock) {
        this.documents = documents;
        this.storage = storage;
        this.pipeline = pipeline;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(properties.ingestion().concurrency(), Thread.ofVirtual().name("ingestion-", 0).factory());
    }

    @TransactionalEventListener
    void on(DocumentReceived event) {
        submit(event.tenantId(), event.documentId());
    }

    /** 把所有还在解析中的文档重新排队。启动完成时自动调用。 */
    @EventListener(ApplicationReadyEvent.class)
    public void resumePending() {
        documents.listParsing().forEach(document -> submit(document.tenantId(), document.id()));
    }

    /** 模型暂时不可用而失败、到了自动重试时间的文档回到“解析中”重新排队（#145 A9）。每分钟检查一次。 */
    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void retryDue() {
        for (var document : documents.listDueForRetry(OffsetDateTime.now(clock))) {
            document.reprocess();
            documents.save(document);
            log.info("文档 {} 第 {} 次处理（模型暂时不可用后自动重试）", document.id(), document.attempts());
            submit(document.tenantId(), document.id());
        }
    }

    private void submit(TenantId tenantId, long documentId) {
        if (inFlight.add(documentId)) {
            executor.execute(() -> {
                try {
                    process(tenantId, documentId);
                } finally {
                    inFlight.remove(documentId);
                }
            });
        }
    }

    private void process(TenantId tenantId, long documentId) {
        var document = documents.find(tenantId, documentId)
                .filter(found -> found.status() == DocumentStatus.PARSING);
        if (document.isEmpty()) {
            return;
        }
        try {
            pipeline.run(document.get(), storage.read(tenantId, document.get().sha256()));
        } catch (RuntimeException e) {
            log.error("event=document_failed document={} kind=error", documentId, e);
            document.get().markFailed("处理时出错，请重新上传；如果反复失败请联系管理员");
            documents.save(document.get());
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
