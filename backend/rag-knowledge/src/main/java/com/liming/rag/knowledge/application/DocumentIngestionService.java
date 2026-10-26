package com.liming.rag.knowledge.application;

import com.liming.rag.common.TenantId;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.knowledge.domain.TextChunker;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
 * <p>目前只支持 UTF-8 的 TXT 和 Markdown，在上传请求内同步完成。PDF、Office 等格式接入
 * docling-serve 后改为任务表异步处理，这里的校验和失败语义保持不变。
 */
@Service
public class DocumentIngestionService {

    /** 单个文件上限 50 MB，与 multipart 配置一致。 */
    public static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    private static final Map<String, String> SUPPORTED_TYPES = Map.of(
            "txt", "text/plain",
            "md", "text/markdown",
            "markdown", "text/markdown");

    private final KnowledgeBaseService knowledgeBases;
    private final DocumentRepository documents;
    private final FileStorage storage;
    private final TextChunker chunker = new TextChunker();

    public DocumentIngestionService(KnowledgeBaseService knowledgeBases, DocumentRepository documents,
            FileStorage storage) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.storage = storage;
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

        String text;
        try {
            text = decodeUtf8(content);
        } catch (CharacterCodingException e) {
            document.markFailed("文件不是 UTF-8 编码，请用“另存为 UTF-8”后重新上传");
            documents.save(document);
            return document;
        }
        var chunks = chunker.chunk(titleOf(filename), text);
        if (chunks.isEmpty()) {
            document.markFailed("文件里没有可检索的文字");
        } else {
            documents.saveChunks(document, chunks);
            document.markReady(chunks.size());
        }
        documents.save(document);
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
                    "目前只支持 TXT 和 Markdown；PDF、Word、PPT、Excel 将在接入文档解析服务后支持");
        }
        return mediaType;
    }

    private static String titleOf(String filename) {
        var dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String decodeUtf8(byte[] content) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
