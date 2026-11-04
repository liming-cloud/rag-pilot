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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 混合检索端到端：上传后分块带向量入库，按问题检索，只返回本知识库的分块。
 *
 * <p>测试用本地哈希向量（见 test 下的 config/application.yml），不访问外部模型。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-search-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class KnowledgeSearchTests {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> HITS =
            new ParameterizedTypeReference<>() {
            };

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("问商品位置时，含答案的分块排第一，其他知识库的同名商品不出现")
    void findsAnswerWithinKnowledgeBaseOnly() {
        long store = createKnowledgeBase("一号店知识库");
        long other = createKnowledgeBase("二号店知识库");
        upload(store, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层，补货时先移出临期批次。");
        upload(store, "收银规范.txt", "收银员交班时要清点备用金，在交班表上签字后再离开收银台。");
        upload(store, "临期商品.txt", "临期商品在到期前三天下架，登记到临期台账并通知店长。");
        upload(other, "二号店陈列.txt", "青柚气泡水在二号店二层 C 区货架。");

        assertThat(jdbc.queryForObject("select count(*) from chunk where embedding is null", Integer.class))
                .isZero();

        var hits = search(store, "青柚气泡水在哪个货架");

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst()).containsEntry("documentName", "商品陈列.txt");
        assertThat((String) hits.getFirst().get("content")).contains("03 号货架");
        assertThat(hits).noneMatch(hit -> "二号店陈列.txt".equals(hit.get("documentName")));
    }

    @Test
    @DisplayName("空问题返回空列表；不存在的知识库返回 404")
    void handlesEmptyQuestionAndMissingKnowledgeBase() {
        long store = createKnowledgeBase("空检索知识库");
        assertThat(search(store, "  ")).isEmpty();

        var missing = http.getForEntity("/api/knowledge-bases/{id}/search?q=货架", String.class, 999_999);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private List<Map<String, Object>> search(long knowledgeBaseId, String question) {
        var response = http.exchange("/api/knowledge-bases/{id}/search?q={q}", HttpMethod.GET, null, HITS,
                knowledgeBaseId, question);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(TestDocuments.awaitProcessed(http, knowledgeBaseId, response.getBody(), null))
                .containsEntry("status", "READY");
    }
}
