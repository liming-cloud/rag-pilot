package com.liming.rag.answering.application.insight;

import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** 知识缺口要归类的记录的查询端口：一段时间内答不上来的和被点踩的（D-047）。 */
public interface GapRecords {

    /** {@code from}（含）到 {@code to}（不含）之间的拒答和差评，最新的在前，最多 {@code limit} 条。 */
    List<GapRecord> list(TenantId tenantId, OffsetDateTime from, OffsetDateTime to, int limit);

    /** 同一范围里一共有多少条，用来判断是否被截断。 */
    long count(TenantId tenantId, OffsetDateTime from, OffsetDateTime to);

    /** 存下问题向量，按记录 id（#145 C6）。 */
    void saveVectors(TenantId tenantId, Map<Long, float[]> vectors);

    /**
     * @param comment 差评时员工的留言
     * @param citedDocuments 当时引用的文档名（拒答没有引用）
     * @param reviewStatus OPEN 或 RESOLVED
     * @param knowledgeBaseIds 当时实际检索的知识库；记下检索范围之前的旧记录为空
     * @param questionVector 记录回答后在后台算的问题向量；还没算（旧记录、后台没算成）时为 null
     */
    record GapRecord(long id, long userId, String question, OffsetDateTime createdAt, boolean refused,
            String comment, List<String> citedDocuments, String reviewStatus, List<Long> knowledgeBaseIds,
            float @Nullable [] questionVector) {
    }
}
