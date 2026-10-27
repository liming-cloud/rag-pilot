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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档入库：校验、存原文件、解析、分块、写入分块。
 *
 * <p>支持 UTF-8 的 TXT、Markdown，以及 PDF、Word（.docx），读取、分块、写入见 {@link IngestionPipeline}。
 * 在上传请求内同步完成（含调用嵌入模型）；大文件变多后再改为任务表异步处理，模型调用也随之移出数据库事务。
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
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final KnowledgeBaseService knowledgeBases;
    private final DocumentRepository documents;
    private final FileStorage storage;
    private final IngestionPipeline pipeline;

    DocumentIngestionService(KnowledgeBaseService knowledgeBases, DocumentRepository documents,
            FileStorage storage, IngestionPipeline pipeline) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.storage = storage;
        this.pipeline = pipeline;
    }

    /**
     * 上传一份文档。格式不支持、重复上传会直接拒绝；内容问题（编码不对、没有文字）会保存为失败文档，
     * 让管理员在列表里看到原因。
     */
    @Transactional
    public Document ingest(TenantId tenantId, long knowledgeBaseId, String filename, byte[] content) {
        var knowledgeBase = knowledgeBases.get(tenantId, knowledgeBaseId);
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
        var document = Document.received(tenantId, knowledgeBase.id(), filename, mediaType, sha256, content.length);
        documents.save(document);

        pipeline.run(document, content);
        return document;
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
                    "目前支持 PDF、Word（.docx）、TXT 和 Markdown；Excel 即将支持，旧版 .doc 请先另存为 .docx");
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
