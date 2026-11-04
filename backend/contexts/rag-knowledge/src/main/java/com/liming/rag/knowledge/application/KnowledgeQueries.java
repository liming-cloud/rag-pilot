package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.TenantId;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** 只读查询端口（D-039）：管理端列表的分页、筛选和统计，以及可访问的库、库名，都不加载聚合。 */
public interface KnowledgeQueries {

    /**
     * 授权给他本人或其角色的库，按创建顺序。口径与检索共用 {@code ChunkSearchMapper.accessibleKnowledgeBases}。
     */
    List<KnowledgeBaseRef> accessible(Grantee grantee);

    /** 这个库是否授权给了他本人或其角色，口径同 {@link #accessible}。 */
    boolean isAccessible(Grantee grantee, long knowledgeBaseId);

    /** 本租户里这些库的名称，不检查授权；不存在的 id 不返回。 */
    Map<Long, String> names(TenantId tenantId, Collection<Long> knowledgeBaseIds);

    /**
     * 知识库列表，按创建顺序。
     *
     * @param grantee 只列授权给他本人或其角色的库；为 null 时列本租户全部（超级管理员）
     * @param keyword 匹配名称，为 null 不筛
     * @param validity EXPIRING、EXPIRED 时只列有这种文档的库，为 null 不筛
     */
    Paged<KnowledgeBaseSummary> knowledgeBases(TenantId tenantId, @Nullable Grantee grantee,
            @Nullable String keyword, Validity.@Nullable State validity, Window window, Paged.Request page);

    /**
     * 知识库里的文档，最新上传的在前。
     *
     * @param keyword 匹配文件名，为 null 不筛
     * @param status 为 null 不筛
     * @param validity 有效期状态，为 null 不筛
     */
    Paged<DocumentRow> documents(TenantId tenantId, long knowledgeBaseId, @Nullable String keyword,
            @Nullable DocumentStatus status, Validity.@Nullable State validity, Window window, Paged.Request page);

    /**
     * 即将到期和已失效的文档数，以及最早到期的 {@code limit} 份。
     *
     * @param grantee 只算授权给他本人或其角色的库；为 null 时算本租户全部（超级管理员）
     */
    ExpirySummary expirySummary(TenantId tenantId, @Nullable Grantee grantee, Window window, int limit);

}
