package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.TenantId;
import java.time.LocalDate;

/** 管理端列表的查询端口（D-039）：分页、筛选，带上列表要显示的统计，不加载聚合。 */
public interface KnowledgeQueries {

    /**
     * 知识库列表，按创建顺序。
     *
     * @param grantee 只列授权给他本人或其角色的库；为 null 时列本租户全部（超级管理员）
     * @param keyword 匹配名称，为 null 不筛
     * @param validity EXPIRING、EXPIRED 时只列有这种文档的库，为 null 不筛
     */
    Paged<KnowledgeBaseSummary> knowledgeBases(TenantId tenantId, Grantee grantee, String keyword,
            Validity.State validity, Window window, Paged.Request page);

    /**
     * 知识库里的文档，最新上传的在前。
     *
     * @param keyword 匹配文件名，为 null 不筛
     * @param status 为 null 不筛
     * @param validity 有效期状态，为 null 不筛
     */
    Paged<DocumentRow> documents(TenantId tenantId, long knowledgeBaseId, String keyword, DocumentStatus status,
            Validity.State validity, Window window, Paged.Request page);

    /**
     * 即将到期和已失效的文档数。
     *
     * @param grantee 只算授权给他本人或其角色的库；为 null 时算本租户全部（超级管理员）
     */
    ExpirySummary expirySummary(TenantId tenantId, Grantee grantee, Window window);

    /** 按哪天判断有效期：今天，以及“即将到期”截止到哪天（含），口径同 {@link Validity#state}。 */
    record Window(LocalDate today, LocalDate warnUntil) {
    }

    /** 知识库列表的一行：文档数、失败数、即将到期数、已失效数、授权给几个角色、几个账号。 */
    record KnowledgeBaseSummary(long id, String name, long documentCount, long failedCount, long expiringCount,
            long expiredCount, long roleGrantCount, long memberCount) {
    }

    record ExpirySummary(long expiring, long expired) {
    }

    /** 文档和它要替换的旧版本的文件名（分页后旧版本不一定在同一页）。 */
    record DocumentRow(Document document, String replacesFilename) {
    }
}
