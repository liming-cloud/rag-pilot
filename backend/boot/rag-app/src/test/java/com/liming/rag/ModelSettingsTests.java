package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型管理（#120、D-043）端到端：重排走真实的 HTTP 调用，对着本地的假服务商。环境变量里的服务商在 /env/v1，
 * 界面添加的服务商在 /db/v1；断言保存后下一次问答的重排请求打到新地址、用新模型、带解密后的密钥，测不通不保存，
 * 库里和接口里都没有密钥明文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-model-settings-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class ModelSettingsTests {

    private static final String PROVIDER_KEY = "sk-provider-secret-9f3c";
    private static final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private static final HttpServer FAKE = startFake();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("rag.ai.base-url", () -> fakeUrl("env"));
        registry.add("rag.ai.api-key", () -> "sk-env-key-0000");
        registry.add("rag.ai.rerank.provider", () -> "openai");
        registry.add("rag.ai.rerank.model", () -> "env-reranker");
        registry.add("rag.settings.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void stopFake() {
        FAKE.stop(0);
    }

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("界面保存重排模型后，下一次问答就打到新服务商、用新模型、带解密后的密钥；测不通不保存；可恢复")
    void switchesRerankWithoutRestart() {
        long kb = createKnowledgeBase();
        upload(kb, "收银规范.txt", "收银员交班时要清点备用金，在交班表上签字后再离开收银台。");
        var overview = get("/api/models");
        assertThat(overview.get("usages").toString()).contains("RERANK").contains("env-reranker")
                .contains("ENVIRONMENT");

        ask(kb);
        assertThat(lastRerank()).startsWith("/env/v1/rerank Bearer sk-env-key-0000").contains("env-reranker");

        long providerId = id(send(HttpMethod.POST, "/api/model-providers",
                Map.of("name", "测试服务商", "baseUrl", fakeUrl("db"), "apiKey", PROVIDER_KEY)));
        var refused = send(HttpMethod.PUT, "/api/models/RERANK", Map.of("providerId", providerId, "model", "missing"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).containsEntry("code", "SETTINGS_UNREACHABLE");
        assertThat(String.valueOf(refused.getBody().get("detail"))).contains("没有保存").contains("404");
        ask(kb);
        assertThat(lastRerank()).as("测不通没有保存，仍用环境变量").startsWith("/env/v1/rerank");

        var saved = send(HttpMethod.PUT, "/api/models/RERANK", Map.of("providerId", providerId, "model", "db-reranker"));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).containsEntry("source", "DATABASE").containsEntry("providerName", "测试服务商");
        ask(kb);
        assertThat(lastRerank()).startsWith("/db/v1/rerank Bearer " + PROVIDER_KEY).contains("db-reranker");

        // 换回环境变量里的服务商，再从变更记录恢复到“测试服务商”。
        send(HttpMethod.PUT, "/api/models/RERANK", Map.of("model", "env-reranker-2"));
        ask(kb);
        assertThat(lastRerank()).startsWith("/env/v1/rerank").contains("env-reranker-2");
        var changes = get("/api/models/changes");
        assertThat(changes).containsEntry("total", 2);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) changes.get("items");
        long toDatabase = ((Number) items.getLast().get("id")).longValue();
        assertThat(send(HttpMethod.POST, "/api/models/changes/" + toDatabase + "/restore", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ask(kb);
        assertThat(lastRerank()).startsWith("/db/v1/rerank").contains("db-reranker");
        assertThat(get("/api/models/changes")).containsEntry("total", 3);

        assertThat(send(HttpMethod.DELETE, "/api/model-providers/" + providerId, null).getStatusCode())
                .as("还在用的服务商不能删").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("密钥加密存库：库里和接口响应都没有明文，只显示后 4 位；能读服务商的模型列表")
    void storesKeysEncrypted() {
        var created = send(HttpMethod.POST, "/api/model-providers",
                Map.of("name", "加密测试", "baseUrl", fakeUrl("db"), "apiKey", PROVIDER_KEY));
        assertThat(created.getBody()).containsEntry("apiKeyLast4", "9f3c");
        long providerId = id(created);

        byte[] stored = jdbc.queryForObject("SELECT api_key_cipher FROM model_provider WHERE id = ?", byte[].class,
                providerId);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("sk-provider");
        var raw = http.getForEntity("/api/models", String.class).getBody();
        assertThat(raw).contains("9f3c").doesNotContain(PROVIDER_KEY).doesNotContain("sk-env-key");

        var models = http.exchange("/api/models/available?providerId=" + providerId, HttpMethod.GET, null,
                List.class);
        assertThat(models.getBody()).containsExactly("db-reranker", "other-model");
        assertThat(lastRequestTo("/db/v1/models")).contains("Bearer " + PROVIDER_KEY);
    }

    private void ask(long kb) {
        var response = http.postForEntity("/api/ask",
                Map.of("question", "交班时要做什么", "knowledgeBaseIds", List.of(kb)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static String lastRerank() {
        synchronized (requests) {
            return requests.reversed().stream().filter(request -> request.contains("/rerank")).findFirst()
                    .orElseThrow();
        }
    }

    private static String lastRequestTo(String path) {
        synchronized (requests) {
            return requests.reversed().stream().filter(request -> request.startsWith(path)).findFirst().orElseThrow();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        var response = http.getForEntity(path, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map<String, Object>> send(HttpMethod method, String path, Object body) {
        ResponseEntity response = http.exchange(path, method, new HttpEntity<>(body), Map.class);
        return response;
    }

    private static long id(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(String.valueOf(response.getBody())).isTrue();
        return ((Number) response.getBody().get("id")).longValue();
    }

    private long createKnowledgeBase() {
        return id(send(HttpMethod.POST, "/api/knowledge-bases", Map.of("name", "模型切换测试库")));
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

    private static String fakeUrl(String prefix) {
        return "http://127.0.0.1:" + FAKE.getAddress().getPort() + "/" + prefix + "/v1";
    }

    /** 假的 OpenAI 兼容服务商：重排给每段资料 0.9 分；模型名为 missing 时返回 404；/models 列两个模型。 */
    private static HttpServer startFake() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var json = JsonMapper.builder().build();
            server.createContext("/", exchange -> {
                var path = exchange.getRequestURI().getPath();
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var auth = exchange.getRequestHeaders().getFirst("Authorization");
                requests.add(path + " " + auth + " " + body);
                int status = 200;
                String reply;
                if (path.endsWith("/models")) {
                    reply = "{\"data\":[{\"id\":\"other-model\"},{\"id\":\"db-reranker\"}]}";
                } else if (body.contains("\"missing\"")) {
                    status = 404;
                    reply = "{\"error\":{\"message\":\"Model does not exist.\"}}";
                } else {
                    int documents = json.readTree(body).path("documents").size();
                    var results = new StringBuilder();
                    for (int i = 0; i < Math.max(1, documents); i++) {
                        results.append(i == 0 ? "" : ",").append("{\"index\":").append(i)
                                .append(",\"relevance_score\":0.9}");
                    }
                    reply = "{\"results\":[" + results + "]}";
                }
                var bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
