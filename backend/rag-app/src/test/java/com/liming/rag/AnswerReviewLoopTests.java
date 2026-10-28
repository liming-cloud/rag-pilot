package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.application.AnswerLogService;
import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 差评处理闭环：管理员看到系统怎么改写的问题、补文档后重新回答确认、把处理过的记录标记为已处理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.storage.root=${java.io.tmpdir}/rag-review-loop-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class AnswerReviewLoopTests {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {
            };

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnswerLogService log;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("回答记录保存改写后的问题，列表里能看到")
    void storesRewrittenQuestion() {
        // 本地确定性模型不会真的改写，这里直接写入一条带改写结果的记录。
        var answer = Answer.refused(Answer.RefusalReason.LOW_RELEVANCE, 0.01).withRewrite(new Answer.Rewrite(
                Answer.Rewrite.Outcome.REWRITTEN, "临期商品打几折？", 9, 800L, 100L, 200L));
        long id = log.record(new TenantId(1), new UserId(1), "临其商品打几着", answer).id();

        var record = review("refused", false).stream().filter(item -> ((Number) item.get("id")).longValue() == id)
                .findFirst().orElseThrow();
        assertThat(record).containsEntry("question", "临其商品打几着").containsEntry("rewrittenQuestion", "临期商品打几折？");
    }

    @Test
    @DisplayName("标记已处理后默认不再显示，可以包含已处理查看或重新打开；处理说明不超过 500 字；店员不能处理")
    void resolvesAndReopens() {
        long id = log.record(new TenantId(1), new UserId(1), "门店几点打烊",
                Answer.refused(Answer.RefusalReason.LOW_RELEVANCE, 0.0)).id();

        assertThat(markReview(id, Map.of("status", "RESOLVED", "note", "已上传营业时间规定"), null))
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ids(review("refused", false))).doesNotContain(id);
        var resolved = review("refused", true).stream().filter(item -> ((Number) item.get("id")).longValue() == id)
                .findFirst().orElseThrow();
        assertThat(resolved).containsEntry("reviewStatus", "RESOLVED").containsEntry("resolutionNote", "已上传营业时间规定");
        assertThat(resolved.get("resolvedAt")).isNotNull();

        assertThat(markReview(id, Map.of("status", "OPEN"), null)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ids(review("refused", false))).contains(id);

        assertThat(markReview(id, Map.of("status", "RESOLVED", "note", "很".repeat(501)), null))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(markReview(id, Map.of("status", "MAYBE"), null)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(markReview(999_999, Map.of("status", "RESOLVED"), null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(markReview(id, Map.of("status", "RESOLVED"), staffToken())).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("补上文档后重新回答，确认已能答出来；重新回答不新增回答记录")
    void rechecksWithCurrentKnowledge() {
        long kb = createKnowledgeBase();
        var first = http.postForEntity("/api/ask", Map.of("question", "交班时要做什么", "knowledgeBaseIds", List.of(kb)),
                Map.class).getBody();
        assertThat(first).containsEntry("refused", true);
        long id = ((Number) first.get("id")).longValue();

        upload(kb, "收银规范.txt", "收银员交班时要清点备用金，在交班表上签字后再离开收银台。");
        int before = jdbc.queryForObject("select count(*) from answer_record", Integer.class);

        var recheck = http.postForEntity("/api/answers/{id}/recheck", null, Map.class, id);
        assertThat(recheck.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recheck.getBody()).containsEntry("refused", false);
        assertThat(recheck.getBody().get("text").toString()).contains("清点备用金");
        assertThat(jdbc.queryForObject("select count(*) from answer_record", Integer.class)).isEqualTo(before);

        assertThat(http.postForEntity("/api/answers/{id}/recheck", null, String.class, 999_999).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private List<Map<String, Object>> review(String filter, boolean includeResolved) {
        var response = http.exchange("/api/answers?filter={filter}&includeResolved={include}", HttpMethod.GET, null,
                LIST, filter, includeResolved);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static List<Long> ids(List<Map<String, Object>> items) {
        return items.stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }

    private HttpStatusCode markReview(long id, Map<String, Object> body, String token) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange("/api/answers/{id}/review", HttpMethod.PATCH, new HttpEntity<>(body, headers),
                String.class, id).getStatusCode();
    }

    private String staffToken() {
        var username = "review-staff-" + System.nanoTime() % 1_000_000;
        http.postForEntity("/api/users", Map.of("username", username, "displayName", "店员",
                "password", "staff-password", "role", "STAFF"), Map.class);
        return TestLogin.token(http, username, "staff-password");
    }

    private long createKnowledgeBase() {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", "复查知识库-" + System.nanoTime()),
                Map.class);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private void upload(long knowledgeBaseId, String filename, String text) {
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
                Map.class, knowledgeBaseId);
        assertThat(TestDocuments.awaitProcessed(http, knowledgeBaseId, response.getBody(), null))
                .containsEntry("status", "READY");
    }
}
