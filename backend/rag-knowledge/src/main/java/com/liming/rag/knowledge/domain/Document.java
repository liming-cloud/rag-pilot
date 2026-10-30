package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.AggregateRoot;
import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 文档聚合：一份上传到知识库的原始文件，以及它的处理状态。
 *
 * <p>状态只能从 PARSING 进入 READY 或 FAILED；失败原因面向管理员展示，必须是可操作的中文说明。
 */
public final class Document implements AggregateRoot {

    /** 失败原因的长度上限，与数据库列宽一致。 */
    public static final int MAX_ERROR_LENGTH = 500;

    private Long id;
    private final TenantId tenantId;
    private final long knowledgeBaseId;
    private final String filename;
    private final String mediaType;
    private final String sha256;
    private final long sizeBytes;
    private DocumentStatus status;
    private String error;
    private int chunkCount;
    private final OffsetDateTime createdAt;
    /** 要替换的旧文档；本文档解析成功后删除它。 */
    private final Long replacesDocumentId;

    private Document(Long id, TenantId tenantId, long knowledgeBaseId, String filename, String mediaType,
            String sha256, long sizeBytes, DocumentStatus status, String error, int chunkCount,
            OffsetDateTime createdAt, Long replacesDocumentId) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId, "租户不能为空");
        this.knowledgeBaseId = knowledgeBaseId;
        this.filename = Objects.requireNonNull(filename, "文件名不能为空");
        this.mediaType = Objects.requireNonNull(mediaType, "文件类型不能为空");
        this.sha256 = Objects.requireNonNull(sha256, "文件摘要不能为空");
        this.sizeBytes = sizeBytes;
        this.status = Objects.requireNonNull(status, "状态不能为空");
        this.error = error;
        this.chunkCount = chunkCount;
        this.createdAt = createdAt;
        this.replacesDocumentId = replacesDocumentId;
    }

    /** 接收一份新上传的文件，进入解析状态。 */
    public static Document received(TenantId tenantId, long knowledgeBaseId, String filename, String mediaType,
            String sha256, long sizeBytes) {
        return received(tenantId, knowledgeBaseId, filename, mediaType, sha256, sizeBytes, null);
    }

    /** 接收一份新版本，解析成功后替换 {@code replacesDocumentId}。 */
    public static Document received(TenantId tenantId, long knowledgeBaseId, String filename, String mediaType,
            String sha256, long sizeBytes, Long replacesDocumentId) {
        return new Document(null, tenantId, knowledgeBaseId, filename, mediaType, sha256, sizeBytes,
                DocumentStatus.PARSING, null, 0, null, replacesDocumentId);
    }

    /** 仓储从数据库恢复聚合时使用，不做状态校验。 */
    public static Document restore(long id, TenantId tenantId, long knowledgeBaseId, String filename,
            String mediaType, String sha256, long sizeBytes, DocumentStatus status, String error, int chunkCount,
            OffsetDateTime createdAt, Long replacesDocumentId) {
        return new Document(id, tenantId, knowledgeBaseId, filename, mediaType, sha256, sizeBytes, status, error,
                chunkCount, createdAt, replacesDocumentId);
    }

    /**
     * 分块入库完成。
     *
     * @throws IllegalStateException 当文档不在解析状态，或分块数不是正数时
     */
    public void markReady(int chunks) {
        requireParsing();
        if (chunks <= 0) {
            throw new IllegalStateException("可检索的文档至少要有一个分块");
        }
        this.status = DocumentStatus.READY;
        this.chunkCount = chunks;
        this.error = null;
    }

    /**
     * 处理失败。
     *
     * @throws IllegalStateException 当文档不在解析状态时
     * @throws IllegalArgumentException 当没有给出失败原因时
     */
    public void markFailed(String reason) {
        requireParsing();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("失败原因不能为空");
        }
        this.status = DocumentStatus.FAILED;
        this.error = reason.length() > MAX_ERROR_LENGTH ? reason.substring(0, MAX_ERROR_LENGTH) : reason;
        this.chunkCount = 0;
    }

    private void requireParsing() {
        if (status != DocumentStatus.PARSING) {
            throw new IllegalStateException("只有解析中的文档才能变更处理结果，当前状态：" + status);
        }
    }

    public void assignId(long newId) {
        if (id != null) {
            throw new IllegalStateException("文档已有标识");
        }
        this.id = newId;
    }

    public Long id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public long knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String filename() {
        return filename;
    }

    public String mediaType() {
        return mediaType;
    }

    public String sha256() {
        return sha256;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Long replacesDocumentId() {
        return replacesDocumentId;
    }

    public DocumentStatus status() {
        return status;
    }

    public String error() {
        return error;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
