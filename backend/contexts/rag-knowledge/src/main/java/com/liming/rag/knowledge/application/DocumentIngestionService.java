package com.liming.rag.knowledge.application;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.knowledge.application.KnowledgeQueries.DocumentRow;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.knowledge.domain.UploadedFile;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档入库：校验、存原文件、记录文档。文件能不能收下由 {@link UploadedFile} 判断（类型、大小、文件名）；
 * 读取、分块、向量化由 {@link IngestionWorker} 在后台按 {@link IngestionPipeline} 完成。
 * 返回给管理端的是 {@link DocumentRow}，不带聚合。
 */
@Service
public class DocumentIngestionService {

    private final KnowledgeBaseService knowledgeBases;
    private final AuditTrail audit;
    private final DocumentRepository documents;
    private final FileStorage storage;
    private final ApplicationEventPublisher events;
    private final DocumentRemover remover;
    private final DocumentCalendar calendar;

    DocumentIngestionService(KnowledgeBaseService knowledgeBases, DocumentRepository documents,
            FileStorage storage, ApplicationEventPublisher events, DocumentRemover remover, DocumentCalendar calendar,
            AuditTrail audit) {
        this.audit = audit;
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.storage = storage;
        this.events = events;
        this.remover = remover;
        this.calendar = calendar;
    }

    /**
     * 上传一份文档；{@code replacesDocumentId} 不为空时作为该文档的新版本，解析成功后替换它，失败时旧版本保留。
     * 有效期（D-046）：日期为 yyyy-MM-dd，空串表示不限；不传（null）时新文档长期有效，新版本继承旧版本的失效日期。
     *
     * <p>格式不支持、重复上传会直接拒绝；返回的文档状态是“解析中”。内容问题（编码不对、没有文字、文件损坏）在后台处理时
     * 发现，保存为失败文档，让管理员在列表里看到原因。
     *
     * @param actor 操作人：只能往自己能管理的库里上传（#135）
     * @throws KnowledgeException 库不存在或操作人不能管理它、要替换的文档不在这个知识库里时为 NOT_FOUND；
     *         日期不合法、文件不合格时为 INVALID；类型不支持时为 UNSUPPORTED_TYPE；重复上传时为 CONFLICT
     */
    @Transactional
    public DocumentRow ingest(TenantId tenantId, UserId actor, long knowledgeBaseId, String filename, byte[] content,
            Long replacesDocumentId, String effectiveFrom, String expiresOn) {
        var knowledgeBase = knowledgeBases.getManageable(tenantId, actor, knowledgeBaseId);
        var previous = replacesDocumentId == null ? null : inKnowledgeBase(tenantId, knowledgeBaseId,
                replacesDocumentId, "要替换的文档不存在");
        var inheritedExpiry = previous == null ? null : previous.validity().expiresOn();
        var validity = validity(date(effectiveFrom, null), date(expiresOn, inheritedExpiry));
        var file = uploaded(filename, content);
        if (documents.existsInKnowledgeBase(knowledgeBase.id(), file.sha256())) {
            throw duplicate(filename);
        }

        storage.store(tenantId, file.sha256(), content);
        var document = Document.received(tenantId, knowledgeBase.id(), file, replacesDocumentId, validity);
        try {
            documents.save(document);
        } catch (DuplicateKeyException e) {
            // 查重之后、插入之前同一份文件被别人传进来了（#135）。原始文件按内容存，正是那份文档的文件，不用删。
            throw duplicate(filename);
        }

        if (previous != null) {
            // 新版本处理成功后自动删除旧版本（D-026）；替换在上传这一刻记审计（#145 C2）。
            audit.record(tenantId, actor, "DOCUMENT_REPLACED", "DOCUMENT", String.valueOf(previous.id()),
                    "知识库“" + knowledgeBase.name() + "”上传 " + file.filename() + " 替换 " + previous.filename());
        }
        // 解析、分块、向量化在事务提交后由后台完成，上传请求只返回“解析中”的文档。
        events.publishEvent(new DocumentReceived(tenantId, document.id()));
        return row(document);
    }

    /**
     * 删除文档及其分块；正在解析的文档删除后，后台处理结束时不会把它写回来。
     *
     * @throws KnowledgeException 库不存在或操作人不能管理它、文档不在这个知识库里时为 NOT_FOUND
     */
    @Transactional
    public void delete(TenantId tenantId, UserId actor, long knowledgeBaseId, long documentId) {
        var knowledgeBase = knowledgeBases.getManageable(tenantId, actor, knowledgeBaseId);
        var document = inKnowledgeBase(tenantId, knowledgeBaseId, documentId, "文档不存在");
        if (!remover.remove(tenantId, documentId)) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "文档不存在");
        }
        audit.record(tenantId, actor, "DOCUMENT_DELETED", "DOCUMENT", String.valueOf(documentId),
                "删除知识库“" + knowledgeBase.name() + "”里的资料 " + document.filename());
    }

    /**
     * 修改有效期（doc:update），保存后立即按新日期参与或退出检索。日期为 yyyy-MM-dd，null 或空串表示不限。
     *
     * @throws KnowledgeException 库不存在或操作人不能管理它、文档不在这个知识库里时为 NOT_FOUND；日期不合法时为 INVALID
     */
    @Transactional
    public DocumentRow changeValidity(TenantId tenantId, UserId actor, long knowledgeBaseId, long documentId,
            String effectiveFrom, String expiresOn) {
        knowledgeBases.getManageable(tenantId, actor, knowledgeBaseId);
        var document = inKnowledgeBase(tenantId, knowledgeBaseId, documentId, "文档不存在");
        document.changeValidity(validity(date(effectiveFrom, null), date(expiresOn, null)));
        documents.save(document);
        return row(document);
    }

    /** 这个知识库里的文档；不在这个库里时按不存在处理。 */
    private Document inKnowledgeBase(TenantId tenantId, long knowledgeBaseId, long documentId, String notFound) {
        return documents.find(tenantId, documentId)
                .filter(document -> document.knowledgeBaseId() == knowledgeBaseId)
                .orElseThrow(() -> new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, notFound));
    }

    private DocumentRow row(Document document) {
        return DocumentRow.of(document, null, calendar.state(document.validity()));
    }

    private static UploadedFile uploaded(String filename, byte[] content) {
        try {
            return UploadedFile.of(filename, content);
        } catch (UploadedFile.UnsupportedTypeException e) {
            throw new KnowledgeException(KnowledgeException.Reason.UNSUPPORTED_TYPE, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, e.getMessage());
        }
    }

    private static KnowledgeException duplicate(String filename) {
        return new KnowledgeException(KnowledgeException.Reason.CONFLICT, "这份文件已经在知识库里了：" + filename);
    }

    /** null 表示没传，用默认值；空串表示不限。 */
    private static LocalDate date(String value, LocalDate absent) {
        if (value == null) {
            return absent;
        }
        if (value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, "日期格式应为 yyyy-MM-dd：" + value);
        }
    }

    private static Validity validity(LocalDate effectiveFrom, LocalDate expiresOn) {
        try {
            return new Validity(effectiveFrom, expiresOn);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, e.getMessage());
        }
    }
}
