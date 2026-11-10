package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.AggregateRoot;
import com.liming.rag.shared.TenantId;
import java.io.Serial;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 文档聚合：一份上传到知识库的原始文件，以及它的处理状态。
 *
 * <p>状态只能从 PARSING 进入 READY 或 FAILED；失败原因面向管理员展示，必须是可操作的中文说明。
 * 模型暂时不可用导致的失败可以重新处理（回到 PARSING），第一次失败后 {@link #AUTO_RETRY_DELAY} 自动重试一次；
 * 文件内容的问题（编码、没有文字、损坏）不能重新处理，要改好文件重新上传（#145 A9）。
 * 有效期（{@link Validity}）决定哪天参与检索，可以随时修改，与处理状态无关。
 * 版本号做乐观锁：读取之后被别的操作改过的文档不能整份保存（#197）。
 */
public final class Document implements AggregateRoot {

    /** 失败原因的长度上限，与数据库列宽一致。 */
    public static final int MAX_ERROR_LENGTH = 500;
    /** 模型暂时不可用而失败后，过多久自动重试一次。 */
    public static final Duration AUTO_RETRY_DELAY = Duration.ofMinutes(10);

    private Long id;
    private long version;
    private final TenantId tenantId;
    private final long knowledgeBaseId;
    private final String filename;
    private final String mediaType;
    private final String sha256;
    private final long sizeBytes;
    private DocumentStatus status;
    private @Nullable String error;
    private int chunkCount;
    private final OffsetDateTime createdAt;
    /** 要替换的旧文档；本文档解析成功后删除它。 */
    private final Long replacesDocumentId;
    private Validity validity;
    /** 失败原因是模型暂时不可用，可以重新处理。 */
    private boolean reprocessable;
    /** 自动重试的时间；不自动重试时为 null。 */
    private @Nullable OffsetDateTime retryAt;
    /** 第几次处理，从 1 开始。 */
    private int attempts;

    private Document(@Nullable Long id, long version, TenantId tenantId, long knowledgeBaseId, String filename, String mediaType,
            String sha256, long sizeBytes, DocumentStatus status, String error, int chunkCount,
            OffsetDateTime createdAt, @Nullable Long replacesDocumentId, Validity validity, Retry retry) {
        this.id = id;
        this.version = version;
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
        this.validity = Objects.requireNonNull(validity, "有效期不能为空");
        this.reprocessable = retry.reprocessable();
        this.retryAt = retry.retryAt();
        this.attempts = retry.attempts();
    }

    /** 保存时发现文档已被别的操作改过（版本号不一致），或后台写处理结果时文档已被删除、不在解析中。 */
    public static final class ConcurrentChangeException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        public ConcurrentChangeException() {
            super("文档刚被别的操作改过");
        }
    }

    /**
     * 重新处理的状态，仓储恢复聚合时用。
     *
     * @param attempts 第几次处理，从 1 开始
     */
    public record Retry(boolean reprocessable, @Nullable OffsetDateTime retryAt, int attempts) {

        public static final Retry FIRST = new Retry(false, null, 1);
    }

    /** 接收一份上传的文件，进入解析状态；{@code replacesDocumentId} 不为空时是它的新版本，解析成功后替换它。 */
    public static Document received(TenantId tenantId, long knowledgeBaseId, UploadedFile file, @Nullable Long replacesDocumentId,
            Validity validity) {
        return new Document(null, 0, tenantId, knowledgeBaseId, file.filename(), file.mediaType(), file.sha256(),
                file.sizeBytes(), DocumentStatus.PARSING, null, 0, null, replacesDocumentId, validity, Retry.FIRST);
    }

    /** 仓储从数据库恢复聚合时使用，不做状态校验。 */
    public static Document restore(long id, long version, TenantId tenantId, long knowledgeBaseId, String filename,
            String mediaType, String sha256, long sizeBytes, DocumentStatus status, String error, int chunkCount,
            OffsetDateTime createdAt, @Nullable Long replacesDocumentId, Validity validity, Retry retry) {
        return new Document(id, version, tenantId, knowledgeBaseId, filename, mediaType, sha256, sizeBytes, status, error,
                chunkCount, createdAt, replacesDocumentId, validity, retry);
    }

    /** 修改有效期，保存后立即按新日期参与或退出检索。 */
    public void changeValidity(Validity changed) {
        this.validity = Objects.requireNonNull(changed, "有效期不能为空");
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
     * @throws IllegalStateException 当文档不在解析状态，或没有给出失败原因时
     */
    public void markFailed(String reason) {
        fail(reason);
        this.reprocessable = false;
        this.retryAt = null;
    }

    /**
     * 模型暂时不可用导致处理失败：可以重新处理；第一次处理失败时 {@link #AUTO_RETRY_DELAY} 后自动重试。
     *
     * @throws IllegalStateException 当文档不在解析状态，或没有给出失败原因时
     */
    public void markModelUnavailable(String reason, OffsetDateTime now) {
        fail(reason);
        this.reprocessable = true;
        this.retryAt = attempts == 1 ? now.plus(AUTO_RETRY_DELAY) : null;
    }

    /**
     * 重新处理：回到解析状态，由后台再跑一遍。
     *
     * @throws IllegalStateException 当文档不是因模型暂时不可用而失败时
     */
    public void reprocess() {
        if (status != DocumentStatus.FAILED || !reprocessable) {
            throw new IllegalStateException("只有因模型暂时不可用而失败的文档才能重新处理");
        }
        this.status = DocumentStatus.PARSING;
        this.error = null;
        this.reprocessable = false;
        this.retryAt = null;
        this.attempts++;
    }

    private void fail(String reason) {
        requireParsing();
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("失败原因不能为空");
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

    /** 仓储写入成功后调用：版本号加一，与数据库一致。 */
    public void versionSaved() {
        version++;
    }

    public Long id() {
        return id;
    }

    public long version() {
        return version;
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

    public Validity validity() {
        return validity;
    }

    public DocumentStatus status() {
        return status;
    }

    public @Nullable String error() {
        return error;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public @Nullable OffsetDateTime createdAt() {
        return createdAt;
    }

    public boolean reprocessable() {
        return reprocessable;
    }

    public @Nullable OffsetDateTime retryAt() {
        return retryAt;
    }

    public int attempts() {
        return attempts;
    }
}
