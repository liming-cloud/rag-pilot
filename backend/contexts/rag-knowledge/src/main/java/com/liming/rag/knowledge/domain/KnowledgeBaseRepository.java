package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.TenantId;
import java.util.Optional;

/** 知识库仓储。所有查询都带租户，防止跨租户读取。列表、名称等只读查询在应用层的 {@code KnowledgeQueries}。 */
public interface KnowledgeBaseRepository {

    /**
     * 新知识库插入并回填标识；已有的更新名称。授权只写聚合记下的这次增减（新加的插入、撤销的删除），不整体覆盖，
     * 两个人同时授权不会互相覆盖。返回不带待写变化的聚合。
     */
    KnowledgeBase save(KnowledgeBase knowledgeBase);

    /** 带上直接授权和角色授权，都按授权先后。 */
    Optional<KnowledgeBase> find(TenantId tenantId, long id);

    boolean existsByName(TenantId tenantId, String name);

    /** 角色被删除时，收回授权给它的全部知识库（跨多个聚合的批量操作，在删除角色的事务里执行）。 */
    void removeGrantsOfRole(TenantId tenantId, long roleId);
}
