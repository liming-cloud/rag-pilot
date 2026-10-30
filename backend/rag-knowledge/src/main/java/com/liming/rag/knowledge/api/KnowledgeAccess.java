package com.liming.rag.knowledge.api;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 访问范围查询：员工是哪些知识库的成员。只用来展示和预选，检索时的权限过滤仍只在 {@link KnowledgeSearch} 里做。
 */
public interface KnowledgeAccess {

    /** 员工是成员的知识库，按名称排序。管理员也只看到自己是成员的库（D-017）。 */
    List<KnowledgeBaseRef> accessible(TenantId tenantId, UserId userId);

    /** 知识库名称，不检查成员关系；只用于显示“会话里选过、后来被收回授权”的库叫什么。不存在的 id 不返回。 */
    Map<Long, String> names(TenantId tenantId, Collection<Long> knowledgeBaseIds);
}
