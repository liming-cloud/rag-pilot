package com.liming.rag.evaluation.application;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** 题集和标准题的存储端口（#224）。可见性（查看人能访问哪些库）由调用方过滤。 */
public interface SuiteStore {

    List<EvalSet> sets(TenantId tenantId);

    Optional<EvalSet> set(TenantId tenantId, long setId);

    EvalSet createSet(TenantId tenantId, UserId creator, String name, String description, OffsetDateTime now);

    /** 改名称和说明，同时更新 {@code updatedAt}。 */
    void updateSet(TenantId tenantId, long setId, String name, String description, OffsetDateTime now);

    void touchSet(TenantId tenantId, long setId, OffsetDateTime now);

    /** 连同题目和运行记录一起删除。 */
    void deleteSet(TenantId tenantId, long setId);

    /** 题集里的全部题（含期望文档），按添加顺序；{@code setId} 为 null 时是本租户的全部题。 */
    List<GoldenQuestion> questions(TenantId tenantId, @Nullable Long setId);

    GoldenQuestion addQuestion(TenantId tenantId, long setId, QuestionDraft draft, OffsetDateTime now);

    void updateQuestion(TenantId tenantId, long questionId, QuestionDraft draft);

    void deleteQuestion(TenantId tenantId, long questionId);

    /** 期望文档被新版本替换：接到新版本；已经同时期望新版本的题只去掉旧版本。返回改动的题数。 */
    int followReplacement(TenantId tenantId, long replacedDocumentId, long documentId);
}
