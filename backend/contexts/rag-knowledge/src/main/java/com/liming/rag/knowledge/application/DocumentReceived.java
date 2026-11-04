package com.liming.rag.knowledge.application;

import com.liming.rag.shared.TenantId;

/** 文档已收下、等待解析。上传事务提交后由 {@link IngestionWorker} 在后台处理。 */
record DocumentReceived(TenantId tenantId, long documentId) {
}
