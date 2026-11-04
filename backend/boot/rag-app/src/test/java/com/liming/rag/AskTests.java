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
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-ask-test-files")
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

        var answered = TestAsk.ask(http, null, List.of(store), "青柚气泡水在哪个货架");
        assertThat(answered).containsEntry("refused", false);
        assertThat((String) answered.get("text")).contains("03 号货架").contains("[1]");
        @SuppressWarnings("unchecked")
        var citations = (List<Map<String, Object>>) answered.get("citations");
        assertThat(citations).singleElement().satisfies(citation -> {
            assertThat(citation).containsEntry("index", 1).containsEntry("documentName", "商品陈列.txt");
            assertThat((String) citation.get("excerpt")).contains("03 号货架");
        });

        var refused = TestAsk.ask(http, null, List.of(store), "员工年假怎么申请");
        assertThat(refused).containsEntry("refused", true).containsEntry("refusalReason", "LOW_RELEVANCE");
        assertThat((List<?>) refused.get("citations")).isEmpty();

        long conversation = TestAsk.conversation(http, null, List.of(store));
        assertThat(http.postForEntity("/api/conversations/{id}/turns/stream", Map.of("question", "  "), String.class,
                conversation).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("流式提问：先发这一轮的 id 和处理阶段，再发 delta，最后是 done；拒答不发 delta")
    void streamsAnswer() {
        long store = createKnowledgeBase("流式知识库");
        upload(store, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。补货时先移出临期批次。");

        var answered = TestAsk.stream(http, null, TestAsk.conversation(http, null, List.of(store)), "青柚气泡水在哪个货架");
        assertThat(answered.getFirst().name()).isEqualTo("turn");
        assertThat(answered).extracting(SseEvents.Event::name).filteredOn(name -> !name.equals("turn") && !name.equals("status"))
                .startsWith("delta").endsWith("done");
        assertThat(TestAsk.answer(answered)).containsEntry("refused", false);
        assertThat(answered.getLast().data()).contains("商品陈列.txt");

        var refused = TestAsk.stream(http, null, TestAsk.conversation(http, null, List.of(store)), "员工年假怎么申请");
        assertThat(refused).extracting(SseEvents.Event::name).doesNotContain("delta").endsWith("done");
        assertThat(TestAsk.answer(refused)).containsEntry("refused", true);
    }

    @Test
    @DisplayName("旧的直接问答接口已下线（#145）：员工只用会话提问，评测也走会话接口")
    void directAskIsGone() {
        assertThat(http.postForEntity("/api/ask", Map.of("question", "货架"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity("/api/ask/stream", Map.of("question", "货架"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
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
