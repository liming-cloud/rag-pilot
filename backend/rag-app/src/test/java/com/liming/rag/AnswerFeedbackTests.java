package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 回答记录与反馈：每次问答都记下来并返回 id；只有提问人本人能点赞点踩。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-feedback-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class AnswerFeedbackTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("问答返回 id 并记录问题、回答和引用；提问人可评价、改评价、撤销，别人看到 404")
    void recordsAnswersAndFeedback() {
        long knowledgeBaseId = createKnowledgeBase();
        upload(knowledgeBaseId, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。补货时先移出临期批次。");

        var answer = http.postForEntity("/api/ask", Map.of("question", "青柚气泡水在哪个货架"), Map.class).getBody();
        long id = ((Number) answer.get("id")).longValue();
        assertThat(jdbc.queryForMap("select question, refused, answer_text from answer_record where id = ?", id))
                .containsEntry("question", "青柚气泡水在哪个货架").containsEntry("refused", false);
        assertThat(jdbc.queryForList("select document_name from answer_citation where answer_id = ?", String.class, id))
                .containsExactly("商品陈列.txt");

        var refused = http.postForEntity("/api/ask", Map.of("question", "员工年假怎么申请"), Map.class).getBody();
        assertThat(refused.get("id")).isNotNull();

        assertThat(feedback(HttpMethod.PUT, id, Map.of("rating", "DOWN", "comment", "实际在 A-05"), null))
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForMap("select rating, feedback_comment from answer_record where id = ?", id))
                .containsEntry("rating", "DOWN").containsEntry("feedback_comment", "实际在 A-05");
        assertThat(feedback(HttpMethod.PUT, id, Map.of("rating", "UP"), null)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("select rating from answer_record where id = ?", String.class, id)).isEqualTo("UP");
        assertThat(feedback(HttpMethod.PUT, id, Map.of("rating", "MAYBE"), null)).isEqualTo(HttpStatus.BAD_REQUEST);

        String other = otherUserToken();
        assertThat(feedback(HttpMethod.PUT, id, Map.of("rating", "DOWN"), other)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(feedback(HttpMethod.PUT, 999_999, Map.of("rating", "DOWN"), null)).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(feedback(HttpMethod.DELETE, id, null, null)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("select rating from answer_record where id = ?", String.class, id)).isNull();
    }

    @Test
    @DisplayName("流式问答的 done 事件也带回答 id")
    void streamIncludesId() {
        createKnowledgeBase();
        var events = SseEvents.post(http, "/api/ask/stream", Map.of("question", "员工年假怎么申请"), null);
        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(events.getLast().data()).containsPattern("\"id\":\\d+");
    }

    @Test
    @DisplayName("管理员按差评、答不上来、全部查看回答记录；店员不能看")
    void adminReviewsAnswers() {
        long knowledgeBaseId = createKnowledgeBase();
        upload(knowledgeBaseId, "收银规范.txt", "收银员交班时要清点备用金，在交班表上签字后再离开收银台。");
        var disliked = http.postForEntity("/api/ask", Map.of("question", "交班时要做什么"), Map.class).getBody();
        feedback(HttpMethod.PUT, ((Number) disliked.get("id")).longValue(),
                Map.of("rating", "DOWN", "comment", "还要锁好钱箱"), null);
        http.postForEntity("/api/ask", Map.of("question", "门店几点打烊"), Map.class);

        var dislikedList = review("disliked", null);
        assertThat(dislikedList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dislikedList.getBody()).contains("交班时要做什么").contains("还要锁好钱箱").contains("收银规范.txt")
                .doesNotContain("门店几点打烊");
        assertThat(review("refused", null).getBody()).contains("门店几点打烊").doesNotContain("交班时要做什么");
        assertThat(review("all", null).getBody()).contains("门店几点打烊").contains("交班时要做什么");
        assertThat(review("whatever", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review("all", otherUserToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private org.springframework.http.ResponseEntity<String> review(String filter, String token) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange("/api/answers?filter={filter}", HttpMethod.GET, new HttpEntity<>(headers), String.class,
                filter);
    }

    private HttpStatusCode feedback(HttpMethod method, long id, Map<String, Object> body, String token) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange("/api/answers/{id}/feedback", method, new HttpEntity<>(body, headers), String.class, id)
                .getStatusCode();
    }

    private String otherUserToken() {
        var username = "other-" + System.nanoTime() % 1_000_000;
        var user = new HashMap<String, Object>(Map.of("username", username, "displayName", "另一位店员",
                "password", "staff-password", "role", "STAFF"));
        http.postForEntity("/api/users", user, Map.class);
        return TestLogin.token(http, username, "staff-password");
    }

    private long createKnowledgeBase() {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", "反馈知识库-" + System.nanoTime()),
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
