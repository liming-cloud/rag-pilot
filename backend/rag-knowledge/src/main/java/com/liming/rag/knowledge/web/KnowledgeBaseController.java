package com.liming.rag.knowledge.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Permissions;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import com.liming.rag.knowledge.application.DocumentIngestionService;
import com.liming.rag.knowledge.application.KnowledgeBaseService;
import com.liming.rag.knowledge.application.KnowledgeBaseService.Member;
import com.liming.rag.knowledge.application.KnowledgeQueries.DocumentRow;
import com.liming.rag.knowledge.application.KnowledgeQueries.KnowledgeBaseSummary;
import com.liming.rag.knowledge.application.Paged;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.shared.UserId;
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
 * 知识库、文档与授权接口。都要登录，租户从登录会话取，每个操作对应一个权限点（D-038）。
 * 员工端选库只列授权给自己或自己角色的库；管理操作也只对这些库有效（超级管理员能管理本租户全部的库）。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
@SaCheckLogin
class KnowledgeBaseController {

    record CreateKnowledgeBaseRequest(String name) {
    }

    record RoleGrantView(long roleId) {
    }

    record KnowledgeBaseView(long id, String name) {
        static KnowledgeBaseView of(KnowledgeBase knowledgeBase) {
            return new KnowledgeBaseView(knowledgeBase.id(), knowledgeBase.name());
        }
    }

    record DocumentView(long id, String filename, String status, String error, int chunkCount, long sizeBytes,
            OffsetDateTime createdAt, Long replacesDocumentId, String replacesFilename) {
        static DocumentView of(Document document, String replacesFilename) {
            return new DocumentView(document.id(), document.filename(), document.status().name(), document.error(),
                    document.chunkCount(), document.sizeBytes(), document.createdAt(), document.replacesDocumentId(),
                    replacesFilename);
        }

        static DocumentView of(DocumentRow row) {
            return of(row.document(), row.replacesFilename());
        }
    }

    /** 分页响应（D-039）：{@code {items, total, page, size}}。 */
    record PageView<T>(List<T> items, long total, int page, int size) {
        static <S, T> PageView<T> of(Paged<S> paged, java.util.function.Function<S, T> mapper) {
            return new PageView<>(paged.items().stream().map(mapper).toList(), paged.total(), paged.page(),
                    paged.size());
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

    /** 员工端选库：授权给自己或自己角色的库。 */
    @SaCheckPermission(Permissions.CHAT_USE)
    @GetMapping
    List<KnowledgeBaseView> list() {
        return knowledgeBases.listAccessible(currentUser.tenantId(), currentUser.userId()).stream()
                .map(KnowledgeBaseView::of).toList();
    }

    /** 管理端列表（D-039）：按名称筛选、分页。 */
    @SaCheckPermission(Permissions.KB_VIEW)
    @GetMapping("/page")
    PageView<KnowledgeBaseSummary> page(@RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return PageView.of(knowledgeBases.page(currentUser.tenantId(), currentUser.userId(), keyword, page, size),
                summary -> summary);
    }

    @SaCheckPermission(Permissions.KB_VIEW)
    @GetMapping("/{knowledgeBaseId}")
    KnowledgeBaseView get(@PathVariable long knowledgeBaseId) {
        return KnowledgeBaseView.of(manageable(knowledgeBaseId));
    }

    @SaCheckPermission(Permissions.KB_CREATE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeBaseView create(@RequestBody CreateKnowledgeBaseRequest request) {
        return KnowledgeBaseView.of(knowledgeBases.create(currentUser.tenantId(), currentUser.userId(), request.name()));
    }

    /** 按文件名、状态筛选，分页（D-039）。 */
    @SaCheckPermission(Permissions.KB_VIEW)
    @GetMapping("/{knowledgeBaseId}/documents")
    PageView<DocumentView> documents(@PathVariable long knowledgeBaseId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return PageView.of(knowledgeBases.documents(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId,
                keyword, status, page, size), DocumentView::of);
    }

    @SaCheckPermission(Permissions.DOC_UPLOAD)
    @PostMapping(path = "/{knowledgeBaseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    DocumentView upload(@PathVariable long knowledgeBaseId, @RequestParam("file") MultipartFile file)
            throws IOException {
        manageable(knowledgeBaseId);
        return DocumentView.of(ingestion.ingest(currentUser.tenantId(), knowledgeBaseId, file.getOriginalFilename(),
                file.getBytes(), null), null);
    }

    /** 上传新版本：新版本处理成功后自动删除旧版本（D-026）。 */
    @SaCheckPermission(Permissions.DOC_REPLACE)
    @PostMapping(path = "/{knowledgeBaseId}/documents/{documentId}/versions",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    DocumentView replace(@PathVariable long knowledgeBaseId, @PathVariable long documentId,
            @RequestParam("file") MultipartFile file) throws IOException {
        manageable(knowledgeBaseId);
        return DocumentView.of(ingestion.ingest(currentUser.tenantId(), knowledgeBaseId, file.getOriginalFilename(),
                file.getBytes(), documentId), null);
    }

    @SaCheckPermission(Permissions.DOC_DELETE)
    @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteDocument(@PathVariable long knowledgeBaseId, @PathVariable long documentId) {
        manageable(knowledgeBaseId);
        ingestion.delete(currentUser.tenantId(), knowledgeBaseId, documentId);
    }

    /** 检索调试与评测用：直接看某个知识库对一个问题召回了哪些分块，同样按授权过滤。 */
    @SaCheckPermission(Permissions.KB_VIEW)
    @GetMapping("/{knowledgeBaseId}/search")
    List<SearchHit> search(@PathVariable long knowledgeBaseId, @RequestParam("q") String question,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        manageable(knowledgeBaseId);
        return search.search(new SearchQuery(currentUser.tenantId(), currentUser.userId(), List.of(knowledgeBaseId),
                question, limit));
    }

    @SaCheckPermission(Permissions.KB_GRANT)
    @GetMapping("/{knowledgeBaseId}/members")
    List<Member> members(@PathVariable long knowledgeBaseId) {
        return knowledgeBases.members(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId);
    }

    @SaCheckPermission(Permissions.KB_GRANT)
    @PutMapping("/{knowledgeBaseId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void grant(@PathVariable long knowledgeBaseId, @PathVariable long userId) {
        knowledgeBases.grant(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId, new UserId(userId));
    }

    @SaCheckPermission(Permissions.KB_GRANT)
    @DeleteMapping("/{knowledgeBaseId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable long knowledgeBaseId, @PathVariable long userId) {
        knowledgeBases.revoke(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId, new UserId(userId));
    }

    @SaCheckPermission(Permissions.KB_GRANT)
    @GetMapping("/{knowledgeBaseId}/role-grants")
    List<RoleGrantView> roleGrants(@PathVariable long knowledgeBaseId) {
        return knowledgeBases.roleGrants(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId).stream()
                .map(RoleGrantView::new).toList();
    }

    @SaCheckPermission(Permissions.KB_GRANT)
    @PutMapping("/{knowledgeBaseId}/role-grants/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void grantRole(@PathVariable long knowledgeBaseId, @PathVariable long roleId) {
        knowledgeBases.grantRole(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId, roleId);
    }

    @SaCheckPermission(Permissions.KB_GRANT)
    @DeleteMapping("/{knowledgeBaseId}/role-grants/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeRole(@PathVariable long knowledgeBaseId, @PathVariable long roleId) {
        knowledgeBases.revokeRole(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId, roleId);
    }

    private KnowledgeBase manageable(long knowledgeBaseId) {
        return knowledgeBases.getManageable(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId);
    }
}
