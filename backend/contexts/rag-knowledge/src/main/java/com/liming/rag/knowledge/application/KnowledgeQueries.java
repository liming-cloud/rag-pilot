package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.api.DocumentFreshness;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.Paged;
import com.liming.rag.shared.TenantId;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** 只读查询端口（D-039）：管理端列表的分页、筛选和统计，以及可访问的库、库名，都不加载聚合。 */
public interface KnowledgeQueries {

    /**
     * 授权给他本人或其角色的库，按创建顺序。口径与检索共用 {@code ChunkSearchMapper.accessibleKnowledgeBases}。
     */
    List<KnowledgeBaseRef> accessible(Grantee grantee);

    /**
     * 能管理的这个库，只查库本身（不含成员和角色授权）。grantee 为 null（超级管理员）时本租户的库都算，否则口径同
     * {@link #accessible}；库不存在或没授权时为空。
     */
    Optional<KnowledgeBaseRef> manageable(TenantId tenantId, @Nullable Grantee grantee, long knowledgeBaseId);

    /** 本租户里这些文档的上传时间和失效日期是否早于 {@code today}，不检查授权；不存在的 id 不返回。 */
    Map<Long, DocumentFreshness> freshness(TenantId tenantId, Collection<Long> documentIds, LocalDate today);

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
     * 知识库里的文档，最新上传的在前，带资料体检的结论（#218）。
     *
     * @param viewer 体检的重复只在他本人或其角色能访问的库里找；为 null 时在本租户全部的库里找（超级管理员）
     */
    Paged<DocumentRow> documents(TenantId tenantId, @Nullable Grantee viewer, long knowledgeBaseId,
            DocumentQuery query, Window window, Paged.Request page);

    /**
     * 文档列表的筛选条件，都可以为 null（不筛）。
     *
     * @param keyword 匹配文件名
     * @param health 体检结论（#218）
     */
    record DocumentQuery(@Nullable String keyword, @Nullable DocumentStatus status, Validity.@Nullable State validity,
            DocumentFinding.@Nullable Filter health) {
    }

    /**
     * 即将到期和已失效的文档数，以及最早到期的 {@code limit} 份。
     *
     * @param grantee 只算授权给他本人或其角色的库；为 null 时算本租户全部（超级管理员）
     */
    ExpirySummary expirySummary(TenantId tenantId, @Nullable Grantee grantee, Window window, int limit);

}
