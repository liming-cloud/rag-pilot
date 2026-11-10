package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.knowledge.application.ingestion.IngestionWorker;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 处理失败的文档（#145 A9）：模型暂时不可用时可以重新处理，10 分钟后自动重试一次；文件本身有问题时不能重新处理，
 * 改好后重传同一份文件会替换失败的那条。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-retry-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class DocumentRetryTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @MockitoBean
    private EmbeddingClient embeddings;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IngestionWorker worker;

    private final AtomicBoolean modelDown = new AtomicBoolean();
    /** 不为 null 时向量化先报告“已开始”，再等放行，用来在处理中途插入别的操作。 */
    private volatile CountDownLatch embeddingStarted;
    private volatile CountDownLatch embeddingReleased;

    @BeforeEach
    void setUp() {
        TestLogin.asAdmin(http);
        modelDown.set(false);
        embeddingStarted = null;
        embeddingReleased = null;
        when(embeddings.embed(anyList())).thenAnswer(invocation -> {
            if (embeddingStarted != null) {
                embeddingStarted.countDown();
                assertThat(embeddingReleased.await(30, TimeUnit.SECONDS)).isTrue();
            }
            if (modelDown.get()) {
                throw new ModelCallException("向量化失败：嵌入模型服务暂时不可用，请稍后重试");
            }
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(text -> new float[EmbeddingClient.DIMENSIONS]).map(vector -> {
                vector[0] = 1;
                return vector;
            }).toList();
        });
    }

    @Test
    @DisplayName("模型暂时不可用：标为失败、可以重新处理、10 分钟后自动重试；重新处理后可检索；没有上传权限 403")
    void reprocessesWhenModelWasUnavailable() {
        long kb = createKnowledgeBase("重试知识库");
        modelDown.set(true);
        var failed = awaitProcessed(kb, upload(kb, "收银规范.txt", "收银员交班时要清点备用金。"));
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("reprocessable", true);
        assertThat((String) failed.get("error")).contains("模型服务暂时不可用");
        var retryAt = OffsetDateTime.parse((String) failed.get("retryAt"));
        assertThat(retryAt).isCloseTo(OffsetDateTime.now().plusMinutes(10),
                org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));

        long id = ((Number) failed.get("id")).longValue();
        var staff = staffToken("retry-staff");
        assertThat(reprocess(kb, id, staff).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).isEqualTo("FAILED");

        modelDown.set(false);
        var reprocessed = reprocess(kb, id, null);
        assertThat(reprocessed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reprocessed.getBody()).containsEntry("status", "PARSING").containsEntry("reprocessable", false);
        assertThat(awaitProcessed(kb, reprocessed.getBody())).containsEntry("status", "READY");
        assertThat(jdbc.queryForObject("select attempts from document where id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("到了自动重试时间的失败文档由后台重新处理；重试后再失败不再自动重试")
    void retriesAutomaticallyOnce() {
        long kb = createKnowledgeBase("自动重试知识库");
        modelDown.set(true);
        long id = ((Number) awaitProcessed(kb, upload(kb, "陈列规范.txt", "鲜食放在冷柜第二层。")).get("id"))
                .longValue();
        jdbc.update("update document set retry_at = now() - interval '1 second' where id = ?", id);

        worker.retryDue();
        var again = awaitProcessed(kb, Map.of("id", id));
        assertThat(again).containsEntry("status", "FAILED").containsEntry("reprocessable", true);
        assertThat(again.get("retryAt")).as("只自动重试一次").isNull();

        modelDown.set(false);
        worker.retryDue();
        assertThat(status(id)).as("没有到期的文档就不动").isEqualTo("FAILED");
    }

    @Test
    @DisplayName("文件本身有问题：不能重新处理（409）；改好后重传同一份文件替换失败的那条")
    void reuploadReplacesFailedDocument() {
        long kb = createKnowledgeBase("重传知识库");
        var failed = awaitProcessed(kb, upload(kb, "空白.txt", " \n\n  \n"));
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("reprocessable", false);
        long id = ((Number) failed.get("id")).longValue();
        assertThat(reprocess(kb, id, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var again = upload(kb, "空白-新.txt", " \n\n  \n");
        assertThat(again).as("同一份文件重传不再报“已存在”").containsEntry("status", "PARSING");
        assertThat(jdbc.queryForObject("select count(*) from document where knowledge_base_id = ?", Integer.class, kb))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from document where id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("后台处理期间管理员改了有效期：处理完成后有效期仍是新值，文档可用（#197）")
    void validityChangedDuringProcessingSurvives() throws InterruptedException {
        long kb = createKnowledgeBase("处理中改有效期知识库");
        embeddingStarted = new CountDownLatch(1);
        embeddingReleased = new CountDownLatch(1);
        var uploaded = upload(kb, "盘点规范.txt", "月底最后一天闭店后全员盘点。");
        long id = ((Number) uploaded.get("id")).longValue();
        assertThat(embeddingStarted.await(30, TimeUnit.SECONDS)).as("后台已读出文档并开始向量化").isTrue();

        var expiresOn = LocalDate.now().plusDays(30).toString();
        var changed = http.exchange("/api/knowledge-bases/{kb}/documents/{id}/validity", HttpMethod.PUT,
                new HttpEntity<>(Map.of("effectiveFrom", "", "expiresOn", expiresOn)), Map.class, kb, id);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        embeddingReleased.countDown();

        assertThat(awaitProcessed(kb, uploaded)).containsEntry("status", "READY");
        assertThat(jdbc.queryForObject("select expires_on from document where id = ?", LocalDate.class, id))
                .isEqualTo(LocalDate.parse(expiresOn));
    }

    private Map<String, Object> awaitProcessed(long kb, Map<String, Object> document) {
        return TestDocuments.awaitProcessed(http, kb, document, null);
    }

    private String status(long id) {
        return jdbc.queryForObject("select status from document where id = ?", String.class, id);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map<String, Object>> reprocess(long kb, long id, String token) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity response = http.postForEntity("/api/knowledge-bases/{kb}/documents/{id}/reprocess",
                new HttpEntity<>(null, headers), Map.class, kb, id);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> upload(long kb, String filename, String text) {
        var file = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.postForEntity("/api/knowledge-bases/{id}/documents", new HttpEntity<>(body, headers),
                Map.class, kb);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private String staffToken(String username) {
        var created = http.postForEntity("/api/users", Map.of("username", username, "displayName", "店员",
                "password", "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http))), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return TestLogin.token(http, username, "staff-password");
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }
}
