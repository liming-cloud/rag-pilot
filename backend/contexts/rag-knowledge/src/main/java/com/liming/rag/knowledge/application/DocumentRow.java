package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 文档列表的一行（#136）：管理端要显示的字段，不带聚合。{@code replacesFilename} 是要替换的旧版本的文件名
 * （分页后旧版本不一定在同一页）；{@code validity} 是按部署的统计时区算好的有效期状态，前端不自己比较日期（D-046）。
 * {@code findings} 是资料体检的结论（#218），空列表表示正常；只在文档列表里给出，解析中、失败的文档和写操作的响应为 null。
 */
public record DocumentRow(long id, String filename, DocumentStatus status, String error, int chunkCount, long sizeBytes,
        OffsetDateTime createdAt, @Nullable Long replacesDocumentId, String replacesFilename, LocalDate effectiveFrom,
        LocalDate expiresOn, Validity.State validity, boolean reprocessable, OffsetDateTime retryAt,
        @Nullable List<DocumentFinding> findings) {

    public static DocumentRow of(Document document, String replacesFilename, Validity.State state) {
        return of(document, replacesFilename, state, null);
    }

    public static DocumentRow of(Document document, String replacesFilename, Validity.State state,
            @Nullable List<DocumentFinding> findings) {
        var validity = document.validity();
        return new DocumentRow(document.id(), document.filename(), document.status(), document.error(),
                document.chunkCount(), document.sizeBytes(), document.createdAt(), document.replacesDocumentId(),
                replacesFilename, validity.effectiveFrom(), validity.expiresOn(), state, document.reprocessable(),
                document.retryAt(), findings);
    }
}
