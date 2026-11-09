package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型用量与费用（#169）端到端：对话、嵌入、重排都接到本地假服务商，假服务商记下自己返回过多少 token；
 * 断言入库向量化、问答、重排的用量落库且与服务商返回的一致，管理端切换模型后记到新模型名下，库里没有问题原文和提问的人，
 * 单价要 model:update（员工 403），改单价记审计、费用按单价估算。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-model-usage-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class ModelUsageTests {

    private static final String QUESTION = "收银员交班时备用金怎么处理";
    private static final String DOCUMENT = "收银员交班时要清点备用金，在交班表上签字后再离开收银台。";
    private static final int EMBEDDING_TOKENS_PER_TEXT = 9;
    private static final int RERANK_TOKENS = 40;
    private static final int PROMPT_TOKENS = 120;
    private static final int COMPLETION_TOKENS = 18;

    /** 假服务商按“用途 · 模型”记下的调用数和返回过的 token。 */
    private static final Map<String, long[]> served = Collections.synchronizedMap(new HashMap<>());
    private static final HttpServer FAKE = startFake();
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        registry.add("rag.ai.api-key", () -> "sk-usage-env-0000");
        registry.add("rag.ai.chat.provider", () -> "openai");
        registry.add("rag.ai.chat.model", () -> "usage-answer");
        registry.add("rag.ai.embedding.provider", () -> "openai");
        registry.add("rag.ai.embedding.model", () -> "usage-embed");
        registry.add("rag.ai.rerank.provider", () -> "openai");
        registry.add("rag.ai.rerank.model", () -> "usage-rerank");
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
    @DisplayName("入库向量化、问答、重排的用量落库，数值与服务商返回的一致；切换模型后记到新模型名下；库里没有问题原文和提问的人")
    @SuppressWarnings("unchecked")
    void recordsUsageUnderTheModelThatServedIt() {
        long kb = createKnowledgeBase();
        var uploaded = upload(kb);
        assertThat(TestDocuments.awaitProcessed(http, kb, uploaded, null)).containsEntry("status", "READY");
        assertThat(TestAsk.ask(http, null, List.of(kb), QUESTION)).containsEntry("refused", false);

        var byModel = awaitUsage("usage-embed", "EMBEDDING · usage-embed");
        for (var key : List.of("EMBEDDING · usage-embed", "ANSWER · usage-answer", "RERANK · usage-rerank")) {
            assertThat(served).as("假服务商应收到 " + key).containsKey(key);
        }
        byModel = awaitUsage("usage-rerank", "RERANK · usage-rerank");
        assertThat(byModel.get("usage-embed")).as("嵌入").containsExactly(served.get("EMBEDDING · usage-embed"));
        assertThat(byModel.get("usage-rerank")).as("重排").containsExactly(served.get("RERANK · usage-rerank"));
        byModel = awaitUsage("usage-answer", "ANSWER · usage-answer");
        assertThat(byModel.get("usage-answer")).as("回答").containsExactly(served.get("ANSWER · usage-answer"));

        // 管理端把回答换到界面添加的服务商和新模型：之后的用量记到新模型名下，旧模型的不变。
        var provider = http.postForEntity("/api/model-providers",
                Map.of("name", "用量测试", "baseUrl", fakeUrl("db"), "apiKey", "sk-usage-db-1111"), Map.class);
        long providerId = ((Number) provider.getBody().get("id")).longValue();
        var oldAnswer = byModel.get("usage-answer").clone();
        assertThat(http.exchange("/api/models/ANSWER", HttpMethod.PUT,
                new HttpEntity<>(Map.of("providerId", providerId, "model", "usage-answer-new")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(TestAsk.ask(http, null, List.of(kb), QUESTION)).containsEntry("refused", false);

        var switched = awaitUsage("usage-answer-new", "ANSWER · usage-answer-new");
        assertThat(switched.get("usage-answer-new")).as("新模型").containsExactly(served.get("ANSWER · usage-answer-new"));
        assertThat(switched.get("usage-answer")).as("旧模型不变").containsExactly(oldAnswer);

        // 只存聚合数字：库里的每个文本列都没有问题原文，也没有账号。
        var texts = jdbc.queryForList("SELECT purpose || ' ' || provider || ' ' || model FROM model_usage_daily",
                String.class);
        assertThat(texts).isNotEmpty().noneMatch(text -> text.contains("备用金") || text.contains("admin"));
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'model_usage_daily'", String.class))
                .containsExactlyInAnyOrder("day", "purpose", "provider", "model", "calls", "input_tokens",
                        "output_tokens");
    }

    @Test
    @DisplayName("改单价要 model:update（员工 403，库里不写）；改价记审计；费用按每百万 token 的单价估算，没填价的模型标出")
    @SuppressWarnings("unchecked")
    void pricesAreAuditedAndGuarded() {
        jdbc.update("DELETE FROM model_usage_daily");
        jdbc.update("INSERT INTO model_usage_daily VALUES (CURRENT_DATE, 'ANSWER', 'api.example.com', 'priced-model', "
                + "3, 2000000, 500000), (CURRENT_DATE, 'RERANK', 'api.example.com', 'free-model', 1, 100, 0)");
        long staffRole = TestLogin.staffRoleId(http);
        http.postForEntity("/api/users", Map.of("username", "usage01", "displayName", "店员", "password",
                "usage-password", "roleIds", List.of(staffRole)), Map.class);
        var staff = new HttpHeaders();
        staff.setBearerAuth(TestLogin.token(http, "usage01", "usage-password"));

        var denied = http.exchange("/api/models/prices", HttpMethod.PUT, new HttpEntity<>(
                Map.of("model", "priced-model", "inputPerMillion", 2, "outputPerMillion", 8), staff), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange("/api/models/usage", HttpMethod.GET, new HttpEntity<>(staff), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_price", Long.class)).isZero();

        var saved = http.exchange("/api/models/prices", HttpMethod.PUT,
                new HttpEntity<>(Map.of("model", "priced-model", "inputPerMillion", 2, "outputPerMillion", 8)),
                Map.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'MODEL_PRICE_CHANGED' "
                + "AND target_id = 'priced-model'", Long.class)).isEqualTo(1);
        var negative = http.exchange("/api/models/prices", HttpMethod.PUT,
                new HttpEntity<>(Map.of("model", "priced-model", "inputPerMillion", -1, "outputPerMillion", 8)),
                Map.class);
        assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var report = http.getForEntity("/api/models/usage?groupBy=MODEL", Map.class).getBody();
        var groups = (List<Map<String, Object>>) report.get("groups");
        // 2 百万输入 × 2 元 + 0.5 百万输出 × 8 元 = 8 元。
        assertThat(groups).filteredOn(group -> String.valueOf(group.get("key")).startsWith("priced-model"))
                .singleElement().satisfies(group -> {
                    assertThat(new BigDecimal(group.get("cost").toString())).isEqualByComparingTo("8");
                    assertThat(group).containsEntry("unpriced", false);
                });
        assertThat(groups).filteredOn(group -> String.valueOf(group.get("key")).startsWith("free-model"))
                .hasSize(1).allSatisfy(group -> assertThat(group).containsEntry("unpriced", true));
        assertThat(((List<Map<String, Object>>) report.get("prices")).stream().map(price -> price.get("model")))
                .contains("priced-model", "free-model");
    }

    /**
     * 后台线程（缺口问题补存向量等）的调用可能在断言时还没记完：等管理端汇总的这个模型追平假服务商返回过的数（最多 10 秒）。
     */
    private Map<String, long[]> awaitUsage(String model, String servedKey) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        var found = usageByModel();
        while (!java.util.Arrays.equals(found.get(model), served.get(servedKey)) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            found = usageByModel();
        }
        return found;
    }

    /** 管理端按模型汇总的 [调用数, 输入 token, 输出 token]。 */
    @SuppressWarnings("unchecked")
    private Map<String, long[]> usageByModel() {
        var report = http.getForEntity("/api/models/usage?groupBy=MODEL", Map.class).getBody();
        var result = new HashMap<String, long[]>();
        for (var group : (List<Map<String, Object>>) report.get("groups")) {
            var model = String.valueOf(group.get("key")).split(" · ")[0];
            result.put(model, new long[] {((Number) group.get("calls")).longValue(),
                    ((Number) group.get("inputTokens")).longValue(), ((Number) group.get("outputTokens")).longValue()});
        }
        return result;
    }

    private long createKnowledgeBase() {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", "用量知识库"), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> upload(long kb) {
        var file = new ByteArrayResource(DOCUMENT.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "收银规范.txt";
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/knowledge-bases/{id}/documents", new HttpEntity<>(body, headers), Map.class,
                kb).getBody();
    }

    private static String fakeUrl(String prefix) {
        return "http://127.0.0.1:" + FAKE.getAddress().getPort() + "/" + prefix + "/v1";
    }

    private static void serve(String purpose, String model, long input, long output) {
        var tally = served.computeIfAbsent(purpose + " · " + model, key -> new long[3]);
        synchronized (tally) {
            tally[0]++;
            tally[1] += input;
            tally[2] += output;
        }
    }

    /** 假的 OpenAI 兼容服务商（环境变量的在 /env/v1，界面添加的在 /db/v1）：对话、嵌入、重排都带 token 用量，并记下返回过多少。 */
    private static HttpServer startFake() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var path = exchange.getRequestURI().getPath();
                var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var model = JSON.readTree(request).path("model").asString("");
                // 管理端保存模型前的连通测试不算业务调用。
                boolean probe = request.contains("连通测试") || request.contains("回复：好");
                if (path.endsWith("/embeddings")) {
                    int texts = Math.max(1, JSON.readTree(request).path("input").size());
                    long tokens = (long) texts * EMBEDDING_TOKENS_PER_TEXT;
                    serve("EMBEDDING", model, tokens, 0);
                    var vector = String.join(",", Collections.nCopies(1024, "0.01"));
                    var data = new StringBuilder();
                    for (int i = 0; i < texts; i++) {
                        data.append(i == 0 ? "" : ",").append("{\"object\":\"embedding\",\"index\":").append(i)
                                .append(",\"embedding\":[").append(vector).append("]}");
                    }
                    respond(exchange, "application/json", "{\"object\":\"list\",\"model\":\"" + model + "\",\"data\":["
                            + data + "],\"usage\":{\"prompt_tokens\":" + tokens + ",\"total_tokens\":" + tokens + "}}");
                } else if (path.endsWith("/rerank")) {
                    if (!probe) {
                        serve("RERANK", model, RERANK_TOKENS, 0);
                    }
                    int documents = Math.max(1, JSON.readTree(request).path("documents").size());
                    var results = new StringBuilder();
                    for (int i = 0; i < documents; i++) {
                        results.append(i == 0 ? "" : ",").append("{\"index\":").append(i)
                                .append(",\"relevance_score\":0.9}");
                    }
                    respond(exchange, "application/json", "{\"results\":[" + results + "],\"meta\":{\"tokens\":"
                            + "{\"input_tokens\":" + RERANK_TOKENS + ",\"output_tokens\":0}}}");
                } else if (path.endsWith("/models")) {
                    respond(exchange, "application/json", "{\"data\":[{\"id\":\"usage-answer-new\"}]}");
                } else if (request.matches("(?s).*\"stream\"\\s*:\\s*true.*")) {
                    serve("ANSWER", model, PROMPT_TOKENS, COMPLETION_TOKENS);
                    var chunk = "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"" + model
                            + "\",";
                    respond(exchange, "text/event-stream", "data: " + chunk + "\"choices\":[{\"index\":0,\"delta\":"
                            + "{\"role\":\"assistant\",\"content\":\"交班时要清点备用金并签字[1]。\"},\"finish_reason\":null}]}\n\n"
                            + "data: " + chunk + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: " + chunk + "\"choices\":[],\"usage\":{\"prompt_tokens\":" + PROMPT_TOKENS
                            + ",\"completion_tokens\":" + COMPLETION_TOKENS + ",\"total_tokens\":"
                            + (PROMPT_TOKENS + COMPLETION_TOKENS) + "}}\n\n" + "data: [DONE]\n\n");
                } else {
                    // 非流式对话：连通测试，或会话标题、改写这类轻量调用。
                    if (!probe) {
                        serve("LIGHT", model, 30, 5);
                    }
                    respond(exchange, "application/json", "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
                            + "\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"好\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":30,"
                            + "\"completion_tokens\":5,\"total_tokens\":35}}");
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        try (exchange) {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
