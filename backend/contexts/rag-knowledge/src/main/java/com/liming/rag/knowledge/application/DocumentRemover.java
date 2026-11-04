package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.shared.TenantId;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 删除一份文档：数据库里的文档和分块（级联），以及租户里不再有文档引用的原始文件。
 * 管理员删除和新版本替换旧版本都走这里；调用方负责事务（没有事务时登记提交回调会失败）。
 *
 * <p>原始文件在事务提交后才删：事务回滚时记录还在，文件也得在。提交后再查一次有没有别的文档引用同一个文件，
 * 删不掉只记日志，留下的孤立文件不影响数据。
 */
@Component
class DocumentRemover {

    private static final Logger log = LoggerFactory.getLogger(DocumentRemover.class);

    private final DocumentRepository documents;
    private final FileStorage storage;

    DocumentRemover(DocumentRepository documents, FileStorage storage) {
        this.documents = documents;
        this.storage = storage;
    }

    /** 返回是否真的删了；文档已经不在时返回 false。 */
    boolean remove(TenantId tenantId, long documentId) {
        var document = documents.find(tenantId, documentId);
        if (document.isEmpty() || !documents.delete(tenantId, documentId)) {
            return false;
        }
        var sha256 = document.get().sha256();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteFileIfUnreferenced(tenantId, sha256);
            }
        });
        return true;
    }

    private void deleteFileIfUnreferenced(TenantId tenantId, String sha256) {
        try {
            if (!documents.existsBySha256(tenantId, sha256)) {
                storage.delete(tenantId, sha256);
            }
        } catch (UncheckedIOException e) {
            log.warn("文档已删除，原始文件 {} 没删掉，留作孤立文件", sha256, e);
        }
    }
}
