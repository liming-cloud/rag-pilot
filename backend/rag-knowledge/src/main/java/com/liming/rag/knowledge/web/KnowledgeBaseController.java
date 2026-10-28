package com.liming.rag.knowledge.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.liming.rag.common.UserId;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Roles;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import com.liming.rag.knowledge.application.DocumentIngestionService;
import com.liming.rag.knowledge.application.KnowledgeBaseService;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库与文档接口。
 *
 * <p>都要登录，租户从登录会话取；建库、上传、检索调试、管理成员只有管理员能用。列表和文档只对知识库成员可见。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
@SaCheckLogin
class KnowledgeBaseController {

    record CreateKnowledgeBaseRequest(String name) {
    }

    record MemberView(long userId) {
    }

    record KnowledgeBaseView(long id, String name) {
        static KnowledgeBaseView of(KnowledgeBase knowledgeBase) {
            return new KnowledgeBaseView(knowledgeBase.id(), knowledgeBase.name());
        }
    }

    record DocumentView(long id, String filename, String status, String error, int chunkCount, long sizeBytes,
            OffsetDateTime createdAt, Long replacesDocumentId) {
        static DocumentView of(Document document) {
            return new DocumentView(document.id(), document.filename(), document.status().name(), document.error(),
                    document.chunkCount(), document.sizeBytes(), document.createdAt(), document.replacesDocumentId());
        }
    }

    private final KnowledgeBaseService knowledgeBases;
    private final DocumentIngestionService ingestion;
    private final KnowledgeSearch search;
    private final CurrentUser currentUser;

    KnowledgeBaseController(KnowledgeBaseService knowledgeBases, DocumentIngestionService ingestion,
            KnowledgeSearch search, CurrentUser currentUser) {
        this.knowledgeBases = knowledgeBases;
        this.ingestion = ingestion;
        this.search = search;
        this.currentUser = currentUser;
    }

    @SaCheckRole(Roles.ADMIN)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeBaseView create(@RequestBody CreateKnowledgeBaseRequest request) {
        return KnowledgeBaseView.of(knowledgeBases.create(currentUser.tenantId(), currentUser.userId(), request.name()));
    }

    @GetMapping
    List<KnowledgeBaseView> list() {
        return knowledgeBases.listAccessible(currentUser.tenantId(), currentUser.userId()).stream().map(KnowledgeBaseView::of).toList();
    }

    @SaCheckRole(Roles.ADMIN)
    @PostMapping(path = "/{knowledgeBaseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    DocumentView upload(@PathVariable long knowledgeBaseId, @RequestParam("file") MultipartFile file,
            @RequestParam(name = "replaces", required = false) Long replaces) throws IOException {
        return DocumentView.of(ingestion.ingest(currentUser.tenantId(), knowledgeBaseId, file.getOriginalFilename(),
                file.getBytes(), replaces));
    }

    @SaCheckRole(Roles.ADMIN)
    @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteDocument(@PathVariable long knowledgeBaseId, @PathVariable long documentId) {
        ingestion.delete(currentUser.tenantId(), knowledgeBaseId, documentId);
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    List<DocumentView> documents(@PathVariable long knowledgeBaseId) {
        knowledgeBases.getAccessible(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId);
        return ingestion.list(currentUser.tenantId(), knowledgeBaseId).stream().map(DocumentView::of).toList();
    }

    /** 检索调试与评测用：直接看某个知识库对一个问题召回了哪些分块。 */
    @SaCheckRole(Roles.ADMIN)
    @GetMapping("/{knowledgeBaseId}/search")
    List<SearchHit> search(@PathVariable long knowledgeBaseId, @RequestParam("q") String question,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        knowledgeBases.getAccessible(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId);
        return search.search(new SearchQuery(currentUser.tenantId(), currentUser.userId(), List.of(knowledgeBaseId),
                question, limit));
    }

    @SaCheckRole(Roles.ADMIN)
    @GetMapping("/{knowledgeBaseId}/members")
    List<MemberView> members(@PathVariable long knowledgeBaseId) {
        return knowledgeBases.members(currentUser.tenantId(), knowledgeBaseId).stream()
                .map(userId -> new MemberView(userId.value())).toList();
    }

    @SaCheckRole(Roles.ADMIN)
    @PutMapping("/{knowledgeBaseId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void grant(@PathVariable long knowledgeBaseId, @PathVariable long userId) {
        knowledgeBases.grant(currentUser.tenantId(), knowledgeBaseId, new UserId(userId));
    }

    @SaCheckRole(Roles.ADMIN)
    @DeleteMapping("/{knowledgeBaseId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable long knowledgeBaseId, @PathVariable long userId) {
        knowledgeBases.revoke(currentUser.tenantId(), knowledgeBaseId, new UserId(userId), currentUser.userId());
    }
}
