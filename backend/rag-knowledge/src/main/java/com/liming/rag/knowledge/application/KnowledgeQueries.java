package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.shared.TenantId;

/** 管理端列表的查询端口（D-039）：分页、筛选，带上列表要显示的统计，不加载聚合。 */
public interface KnowledgeQueries {

    /**
     * 知识库列表，按创建顺序。
     *
     * @param grantee 只列授权给他本人或其角色的库；为 null 时列本租户全部（超级管理员）
     * @param keyword 匹配名称，为 null 不筛
     */
    Paged<KnowledgeBaseSummary> knowledgeBases(TenantId tenantId, Grantee grantee, String keyword,
            Paged.Request page);

    /**
     * 知识库里的文档，最新上传的在前。
     *
     * @param keyword 匹配文件名，为 null 不筛
     * @param status 为 null 不筛
     */
    Paged<DocumentRow> documents(TenantId tenantId, long knowledgeBaseId, String keyword, DocumentStatus status,
            Paged.Request page);

    /** 知识库列表的一行：文档数、失败数、授权给几个角色、几个账号。 */
    record KnowledgeBaseSummary(long id, String name, long documentCount, long failedCount, long roleGrantCount,
            long memberCount) {
    }

    /** 文档和它要替换的旧版本的文件名（分页后旧版本不一定在同一页）。 */
    record DocumentRow(Document document, String replacesFilename) {
    }
}
