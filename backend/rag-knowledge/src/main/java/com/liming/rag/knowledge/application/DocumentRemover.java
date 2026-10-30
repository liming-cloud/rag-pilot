package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.shared.TenantId;
import org.springframework.stereotype.Component;

/**
 * 删除一份文档：数据库里的文档和分块（级联），以及租户里不再有文档引用的原始文件。
 * 管理员删除和新版本替换旧版本都走这里；调用方负责事务。
 */
@Component
class DocumentRemover {

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
        if (!documents.existsBySha256(tenantId, document.get().sha256())) {
            storage.delete(tenantId, document.get().sha256());
        }
        return true;
    }
}
