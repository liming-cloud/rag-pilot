package com.liming.rag.answering.domain;

import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * 回答中 [index] 对应的出处。
 *
 * @param excerpt 分块原文，用于“查看引用原文”
 * @param documentUpdatedAt 查看历史回答时补上的资料上传时间，文档已不在时为 null；不随回答记录保存
 * @param documentState 同上，查看时补上，不保存
 */
public record Citation(int index, long chunkId, long documentId, String documentName, String headingPath,
        @Nullable String excerpt, @Nullable OffsetDateTime documentUpdatedAt, DocumentState documentState) {

    /** 引用的资料现在的情况：已过失效日期，或文档已不在（被新版本替换或删除）。 */
    public enum DocumentState {
        CURRENT, EXPIRED, REPLACED
    }

    public Citation(int index, long chunkId, long documentId, String documentName, String headingPath,
            @Nullable String excerpt) {
        this(index, chunkId, documentId, documentName, headingPath, excerpt, null, DocumentState.CURRENT);
    }
}
