package com.liming.rag.knowledge.api;

import com.liming.rag.shared.TenantId;

/**
 * 新版本解析成功、旧版本已删除（#224）：引用了旧版本的模块据此接到新版本。和删除旧版本在同一个事务里发出，
 * 监听方用同步监听（同一事务），失败时整次替换回滚。
 */
public record DocumentReplaced(TenantId tenantId, long replacedDocumentId, long documentId) {
}
