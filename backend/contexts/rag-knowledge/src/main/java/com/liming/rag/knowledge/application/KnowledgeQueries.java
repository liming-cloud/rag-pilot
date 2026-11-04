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

    /** 按哪天判断有效期：今天，以及“即将到期”截止到哪天（含），口径同 {@link Validity#state}。 */
    record Window(LocalDate today, LocalDate warnUntil) {
    }

    /** 知识库列表的一行：文档数、失败数、即将到期数、已失效数、授权给几个角色、几个账号。 */
    record KnowledgeBaseSummary(long id, String name, long documentCount, long failedCount, long expiringCount,
            long expiredCount, long roleGrantCount, long memberCount) {
    }

    /** @param documents 最早到期的若干份（已失效、即将到期，按失效日期先后），供运营概览和周报列出 */
    record ExpirySummary(long expiring, long expired, List<ExpiringDocument> documents) {
    }

    /** @param validity EXPIRING 或 EXPIRED */
    record ExpiringDocument(long knowledgeBaseId, String knowledgeBaseName, long documentId, String filename,
            LocalDate expiresOn, Validity.State validity) {
    }

    /**
     * 文档列表的一行（#136）：管理端要显示的字段，不带聚合。{@code replacesFilename} 是要替换的旧版本的文件名
     * （分页后旧版本不一定在同一页）；{@code validity} 是按部署的统计时区算好的有效期状态，前端不自己比较日期（D-046）。
     */
    record DocumentRow(long id, String filename, DocumentStatus status, String error, int chunkCount, long sizeBytes,
            OffsetDateTime createdAt, @Nullable Long replacesDocumentId, String replacesFilename, LocalDate effectiveFrom,
            LocalDate expiresOn, Validity.State validity, boolean reprocessable, OffsetDateTime retryAt) {

        public static DocumentRow of(Document document, String replacesFilename, Validity.State state) {
            var validity = document.validity();
            return new DocumentRow(document.id(), document.filename(), document.status(), document.error(),
                    document.chunkCount(), document.sizeBytes(), document.createdAt(), document.replacesDocumentId(),
                    replacesFilename, validity.effectiveFrom(), validity.expiresOn(), state, document.reprocessable(),
                    document.retryAt());
        }
    }
}
