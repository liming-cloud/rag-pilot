package com.liming.rag.knowledge.api;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 文档的只读查询，供其他模块引用文档（#224 标准题的期望文档）。按员工列文档时只看员工能访问的库（同 {@link KnowledgeAccess}）；
 * 检索仍只经 {@link KnowledgeSearch}。
 */
public interface KnowledgeDocuments {

    /** 还在的文档，不检查成员关系，只给引用方判断“文档是否已删除”和显示文件名；已删除的 id 不返回。 */
    Map<Long, DocumentRef> existing(TenantId tenantId, Collection<Long> documentIds);

    /**
     * 员工能访问的库里已入库的文档，文件名包含 {@code keyword}（为空不筛），按文件名排序，最多 {@code limit} 份；
     * 库不存在或无权访问时为空。
     */
    List<DocumentRef> ready(TenantId tenantId, UserId userId, long knowledgeBaseId, @Nullable String keyword, int limit);

    /** 同上，按文件名精确匹配（导入时把文件名换成文档）。 */
    List<DocumentRef> named(TenantId tenantId, UserId userId, long knowledgeBaseId, Collection<String> filenames);
}
