package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 问答端到端：上传 → 检索 → 重排 → 生成 → 引用。模型用本地确定性实现（见 test 下的 config/application.yml）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.storage.root=${java.io.tmpdir}/rag-ask-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class AskTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("能回答的问题带引用出处；知识库没有的内容拒答；空问题返回 400")
    void answersWithCitationsAndRefusesUnknown() {
        long store = createKnowledgeBase("问答知识库");
        upload(store, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。补货时先移出临期批次。");
        upload(store, "收银规范.txt", "收银员交班时要清点备用金，在交班表上签字后再离开收银台。");

        var answered = ask(store, "青柚气泡水在哪个货架");
        assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answered.getBody()).containsEntry("refused", false);
        assertThat((String) answered.getBody().get("text")).contains("03 号货架").contains("[1]");
        @SuppressWarnings("unchecked")
        var citations = (List<Map<String, Object>>) answered.getBody().get("citations");
        assertThat(citations).singleElement().satisfies(citation -> {
            assertThat(citation).containsEntry("index", 1).containsEntry("documentName", "商品陈列.txt");
            assertThat((String) citation.get("excerpt")).contains("03 号货架");
        });

        var refused = ask(store, "员工年假怎么申请");
        assertThat(refused.getBody()).containsEntry("refused", true).containsEntry("refusalReason", "LOW_RELEVANCE");
        assertThat((List<?>) refused.getBody().get("citations")).isEmpty();

        assertThat(ask(store, "  ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("流式接口先发 delta 再发 done；拒答只发 done")
    void streamsAnswer() {
        long store = createKnowledgeBase("流式知识库");
        upload(store, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。补货时先移出临期批次。");

        var answered = SseEvents.post(http, "/api/ask/stream", Map.of("question", "青柚气泡水在哪个货架"), null);
        assertThat(answered).extracting(SseEvents.Event::name).startsWith("delta").endsWith("done");
        assertThat(answered.getLast().data()).contains("\"refused\":false").contains("商品陈列.txt");

        var refused = SseEvents.post(http, "/api/ask/stream", Map.of("question", "员工年假怎么申请"), null);
        assertThat(refused).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("done");
            assertThat(event.data()).contains("\"refused\":true");
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private org.springframework.http.ResponseEntity<Map<String, Object>> ask(long knowledgeBaseId, String question) {
        org.springframework.http.ResponseEntity response = http.postForEntity("/api/ask",
                Map.of("question", question, "knowledgeBaseIds", List.of(knowledgeBaseId)), Map.class);
        return response;
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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
        assertThat(response.getBody()).containsEntry("status", "READY");
    }
}
