package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.knowledge.application.ingestion.DocumentIngestionService;
import com.liming.rag.knowledge.application.ingestion.DocumentIngestionService.Upload;
import com.liming.rag.knowledge.application.KnowledgeException;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 删除文档与上传新版本：删除后旧内容不再被检索和引用；新版本解析成功才替换旧版本，失败时旧版本保留。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DocumentLifecycleTests {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${rag.knowledge.storage.root}")
    private String storageRoot;

    @Autowired
    private DocumentIngestionService ingestion;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("删除文档：分块和原始文件一起删掉，之后检索不到、问答也不再引用")
    void deletesDocument() {
        long kb = createKnowledgeBase("删除知识库");
        var doc = uploadReady(kb, "收银规范.txt", "收银员交班时要清点备用金，在交班表上签字后再离开收银台。", null);
        long id = ((Number) doc.get("id")).longValue();
        var sha = jdbc.queryForObject("select sha256 from document where id = ?", String.class, id);
        assertThat(ask("交班时要做什么", kb)).containsEntry("refused", false);

        assertThat(delete(kb, id)).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(documents(kb)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from chunk where document_id = ?", Integer.class, id)).isZero();
        assertThat(Files.exists(Path.of(storageRoot, "1", sha.substring(0, 2), sha))).isFalse();
        assertThat(ask("交班时要做什么", kb)).containsEntry("refused", true);
        assertThat(delete(kb, id)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("上传新版本：解析成功后旧版本被替换，只能检索到新内容")
    void replacesWithNewVersion() {
        long kb = createKnowledgeBase("替换知识库");
        var oldDoc = uploadReady(kb, "备用金规定.txt", "开班备用金为 800 元，交班时当面清点。", null);
        long oldId = ((Number) oldDoc.get("id")).longValue();

        uploadReady(kb, "备用金规定-2026版.txt", "开班备用金调整为 1000 元，交班时当面清点。", oldId);

        // 旧版本删除后，新版本上的 replacesDocumentId 随外键置空；列表里只剩新版本。
        assertThat(documents(kb)).extracting(d -> d.get("filename")).containsExactly("备用金规定-2026版.txt");
        var answer = ask("开班备用金是多少", kb);
        assertThat(answer.get("text").toString()).contains("1000").doesNotContain("800");
    }

    @Test
    @DisplayName("新版本解析失败时旧版本保留；替换别的知识库里的文档返回 404")
    void keepsOldVersionWhenNewFails() {
        long kb = createKnowledgeBase("失败保留知识库");
        long other = createKnowledgeBase("另一个知识库");
        var oldDoc = uploadReady(kb, "陈列规范.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。", null);
        long oldId = ((Number) oldDoc.get("id")).longValue();

        var failed = upload(kb, "陈列规范-新版.txt", new byte[] {(byte) 0xC1, (byte) 0xD9, (byte) 0xC6}, oldId);
        var processed = TestDocuments.awaitProcessed(http, kb, failed.getBody(), null);
        assertThat(processed).containsEntry("status", "FAILED");
        assertThat(((Number) processed.get("replacesDocumentId")).longValue()).isEqualTo(oldId);
        assertThat(processed).as("分页后旧版本不一定在同一页，列表直接给出它的文件名")
                .containsEntry("replacesFilename", "陈列规范.txt");
        assertThat(documents(kb)).extracting(d -> d.get("filename")).contains("陈列规范.txt");

        var wrongKb = upload(other, "陈列规范-新版.txt", "新内容".getBytes(StandardCharsets.UTF_8), oldId);
        assertThat(wrongKb.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("应用服务自己判定资源授权：不能管理这个库的人直接调用上传、替换、改有效期、删除都按不存在处理，什么都不写（#135）")
    void ingestionChecksResourceAccessItself() throws Exception {
        long kb = createKnowledgeBase("授权下沉知识库");
        var doc = uploadReady(kb, "收银规范.txt", "收银员交班时要清点备用金。", null);
        long docId = ((Number) doc.get("id")).longValue();
        var tenant = new TenantId(1);
        var outsider = new UserId(createStaff("ingest01"));
        var content = "外人想塞进来的内容".getBytes(StandardCharsets.UTF_8);
        int documentsBefore = jdbc.queryForObject("select count(*) from document where knowledge_base_id = ?",
                Integer.class, kb);

        assertNotFound(() -> ingestion.ingest(tenant, outsider, Upload.of(kb, "外人.txt", content)));
        assertNotFound(() -> ingestion.ingest(tenant, outsider, new Upload(kb, "外人.txt", content, docId, null, null)));
        assertNotFound(() -> ingestion.changeValidity(tenant, outsider, kb, docId, "2020-01-01", "2020-01-02"));
        assertNotFound(() -> ingestion.delete(tenant, outsider, kb, docId));

        assertThat(jdbc.queryForObject("select count(*) from document where knowledge_base_id = ?", Integer.class, kb))
                .isEqualTo(documentsBefore);
        assertThat(jdbc.queryForObject("select expires_on is null from document where id = ?", Boolean.class, docId))
                .as("有效期没被改").isTrue();
        var sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        assertThat(Files.exists(Path.of(storageRoot, "1", sha.substring(0, 2), sha))).as("原始文件没写入").isFalse();
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(KnowledgeException.class,
                e -> assertThat(e.reason()).isEqualTo(KnowledgeException.Reason.NOT_FOUND));
    }

    private long createStaff(String username) {
        var created = http.postForEntity("/api/users", Map.of("username", username, "displayName", "店员",
                "password", "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http))), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private Map<String, Object> uploadReady(long kb, String filename, String text, Long replaces) {
        var response = upload(kb, filename, text.getBytes(StandardCharsets.UTF_8), replaces);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var processed = TestDocuments.awaitProcessed(http, kb, response.getBody(), null);
        assertThat(processed).containsEntry("status", "READY");
        return processed;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private org.springframework.http.ResponseEntity<Map<String, Object>> upload(long kb, String filename,
            byte[] content, Long replaces) {
        var file = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var path = "/api/knowledge-bases/{id}/documents" + (replaces == null ? "" : "/" + replaces + "/versions");
        org.springframework.http.ResponseEntity response = http.postForEntity(path, new HttpEntity<>(body, headers),
                Map.class, kb);
        return response;
    }

    private HttpStatus delete(long kb, long id) {
        return (HttpStatus) http.exchange("/api/knowledge-bases/{kb}/documents/{id}", HttpMethod.DELETE, null,
                String.class, kb, id).getStatusCode();
    }

    private List<Map<String, Object>> documents(long kb) {
        return TestDocuments.all(http, kb, new HttpHeaders());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ask(String question, long kb) {
        return TestAsk.ask(http, null, List.of(kb), question);
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }
}
