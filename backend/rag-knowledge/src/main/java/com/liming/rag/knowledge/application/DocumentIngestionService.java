package com.liming.rag.knowledge.application;

import com.liming.rag.common.TenantId;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档入库：校验、存原文件、解析、分块、写入分块。
 *
 * <p>支持 UTF-8 的 TXT、Markdown，以及 PDF、Word（.docx）、Excel（.xlsx / .xls）。上传只做校验、存文件、记录文档，
 * 读取、分块、向量化由 {@link IngestionWorker} 在后台按 {@link IngestionPipeline} 完成。
 */
@Service
public class DocumentIngestionService {

    /** 单个文件上限 50 MB，与 multipart 配置一致。 */
    public static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    private static final Map<String, String> SUPPORTED_TYPES = Map.of(
            "txt", "text/plain",
            "md", "text/markdown",
            "markdown", "text/markdown",
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xls", "application/vnd.ms-excel");

    private final KnowledgeBaseService knowledgeBases;
    private final DocumentRepository documents;
    private final FileStorage storage;
    private final ApplicationEventPublisher events;
    private final DocumentRemover remover;

    DocumentIngestionService(KnowledgeBaseService knowledgeBases, DocumentRepository documents,
            FileStorage storage, ApplicationEventPublisher events, DocumentRemover remover) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.storage = storage;
        this.events = events;
        this.remover = remover;
    }

    /**
     * 上传一份文档。格式不支持、重复上传会直接拒绝；返回的文档状态是“解析中”。内容问题（编码不对、没有文字、
     * 文件损坏）在后台处理时发现，保存为失败文档，让管理员在列表里看到原因。
     */
    @Transactional
    public Document ingest(TenantId tenantId, long knowledgeBaseId, String filename, byte[] content) {
        return ingest(tenantId, knowledgeBaseId, filename, content, null);
    }

    /**
     * 上传一份文档；{@code replacesDocumentId} 不为空时作为该文档的新版本，解析成功后替换它，失败时旧版本保留。
     *
     * @throws KnowledgeException 要替换的文档不在这个知识库里时为 NOT_FOUND
     */
    @Transactional
    public Document ingest(TenantId tenantId, long knowledgeBaseId, String filename, byte[] content,
            Long replacesDocumentId) {
        var knowledgeBase = knowledgeBases.get(tenantId, knowledgeBaseId);
        if (replacesDocumentId != null && documents.find(tenantId, replacesDocumentId)
                .filter(old -> old.knowledgeBaseId() == knowledgeBaseId).isEmpty()) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "要替换的文档不存在");
        }
        var mediaType = mediaTypeOf(filename);
        if (content.length == 0) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, "文件是空的");
        }
        if (content.length > MAX_SIZE_BYTES) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, "单个文件不能超过 50 MB");
        }
        var sha256 = sha256(content);
        if (documents.existsInKnowledgeBase(knowledgeBase.id(), sha256)) {
            throw new KnowledgeException(KnowledgeException.Reason.CONFLICT, "这份文件已经在知识库里了：" + filename);
        }

        storage.store(tenantId, sha256, content);
        var document = Document.received(tenantId, knowledgeBase.id(), filename, mediaType, sha256, content.length,
                replacesDocumentId);
        documents.save(document);

        // 解析、分块、向量化在事务提交后由后台完成，上传请求只返回“解析中”的文档。
        events.publishEvent(new DocumentReceived(tenantId, document.id()));
        return document;
    }

    /**
     * 删除文档及其分块；正在解析的文档删除后，后台处理结束时不会把它写回来。
     *
     * @throws KnowledgeException 文档不在这个知识库里时为 NOT_FOUND
     */
    @Transactional
    public void delete(TenantId tenantId, long knowledgeBaseId, long documentId) {
        knowledgeBases.get(tenantId, knowledgeBaseId);
        var inKnowledgeBase = documents.find(tenantId, documentId)
                .filter(document -> document.knowledgeBaseId() == knowledgeBaseId).isPresent();
        if (!inKnowledgeBase || !remover.remove(tenantId, documentId)) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "文档不存在");
        }
    }

    @Transactional(readOnly = true)
    public List<Document> list(TenantId tenantId, long knowledgeBaseId) {
        knowledgeBases.get(tenantId, knowledgeBaseId);
        return documents.list(tenantId, knowledgeBaseId);
    }

    private static String mediaTypeOf(String filename) {
        if (filename != null && filename.length() > 255) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, "文件名不能超过 255 个字符");
        }
        var dot = filename == null ? -1 : filename.lastIndexOf('.');
        var extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        var mediaType = SUPPORTED_TYPES.get(extension);
        if (mediaType == null) {
            throw new KnowledgeException(KnowledgeException.Reason.UNSUPPORTED_TYPE,
                    "目前支持 PDF、Word（.docx）、Excel（.xlsx / .xls）、TXT 和 Markdown；旧版 .doc 请先另存为 .docx");
        }
        return mediaType;
    }



    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
