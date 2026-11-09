package com.liming.rag.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.TestDatabase;
import com.liming.rag.ai.ToolCaller;
import com.liming.rag.ai.ToolJwks;
import com.liming.rag.ai.tool.ToolCallException.Reason;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 工具调用通路（#180，D-058）：真实的 {@link ToolCaller} 带着提问人的令牌调用模拟新零售 MCP 服务，
 * 服务按令牌做第二道校验。范围外、错误受众、过期、工具名不符都被拒，并且断言没有拿到数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class ToolCallTests {

    private static final String AUDIENCE = "https://erp.example.com/mcp";
    private static final String SIGNING_KEY = ToolSigningKeys.generateJwk("test-key");
    private static final AtomicReference<ToolJwks> KEYS = new AtomicReference<>();
    private static RetailMcpServer retail;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @BeforeAll
    static void startRetail() throws Exception {
        retail = new RetailMcpServer(AUDIENCE, () -> jwks());
    }

    @AfterAll
    static void stopRetail() {
        retail.close();
    }

    @DynamicPropertySource
    static void tools(DynamicPropertyRegistry registry) {
        registry.add("rag.ai.tools.signing-key", () -> SIGNING_KEY);
        registry.add("rag.ai.tools.endpoints.retail.url", () -> retail.url());
        registry.add("rag.ai.tools.endpoints.retail.audience", () -> AUDIENCE);
    }

    @Autowired
    private ToolJwks jwks;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private ToolCaller caller;
    @Autowired
    private ToolTokenIssuer issuer;

    @BeforeEach
    void connect() {
        KEYS.set(jwks);
        retail.served.clear();
    }

    @AfterEach
    void resetRetail() {
        retail.audience = AUDIENCE;
        retail.skew = Duration.ZERO;
    }

    private static Map<String, Object> jwks() {
        return KEYS.get().jwks();
    }

    private static ToolCall inventory(String store, List<String> scope) {
        return new ToolCall("retail", "get_store_inventory", Map.of("store_id", store, "sku", "A1001"), "u-42",
                Map.of("stores", scope));
    }

    @Test
    @DisplayName("JWKS 接口不用登录，只有公钥、没有私钥，（验签由范围内调用的测试覆盖）")
    void publishesPublicKeysWithoutLogin() {
        var response = http.getForEntity("/api/integration/jwks.json", Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked") // JWKS 的结构由 ToolJwks 约定。
        var keys = (List<Map<String, Object>>) response.getBody().get("keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).containsEntry("kid", "test-key").containsEntry("alg", "ES256")
                .doesNotContainKey("d");
        assertThat(response.getBody()).isEqualTo(jwks.jwks());
    }

    @Test
    @DisplayName("其他 /api 接口仍要登录：未登录访问返回 401")
    void otherEndpointsStillRequireLogin() {
        assertThat(http.getForEntity("/api/knowledge-bases", String.class).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("范围内的门店：返回带来源、条件、数据截至时间的结构化结果，令牌编号与模拟服务的日志对得上")
    void returnsDataInsideScope() {
        var result = caller.call(inventory("S001", List.of("S001")));

        assertThat(result.content().get("data")).isEqualTo(
                List.of(Map.of("store_id", "S001", "sku", "A1001", "name", "鲜牛奶 1L", "quantity", 24)));
        assertThat(result.content()).containsKeys("source", "query", "asOf", "queriedAt", "truncated");
        assertThat(result.content().get("source")).isEqualTo(Map.of("system", "ERP", "dataset", "store_inventory"));
        assertThat(retail.served).containsExactly(result.tokenId() + " get_store_inventory rows=1");
    }

    @Test
    @DisplayName("范围外的门店：模拟服务按令牌拒绝（out_of_scope），调用方拿不到数据")
    void rejectsStoreOutsideScope() {
        assertThatThrownBy(() -> caller.call(inventory("S002", List.of("S001"))))
                .isInstanceOfSatisfying(ToolCallException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.TOOL_ERROR);
                    assertThat(e.toolCode()).isEqualTo("out_of_scope");
                });
        assertThat(retail.served).hasSize(1).allMatch(line -> line.endsWith("out_of_scope"));
    }

    @Test
    @DisplayName("令牌的受众不是该服务：401，抛 REJECTED，没有拿到数据，服务没有执行查询")
    void rejectsWrongAudience() {
        retail.audience = "https://other.example.com/mcp";

        assertThatThrownBy(() -> caller.call(inventory("S001", List.of("S001"))))
                .isInstanceOfSatisfying(ToolCallException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.REJECTED));
        assertThat(retail.served).isEmpty();
    }

    @Test
    @DisplayName("令牌过期（服务的时钟比签发晚 2 分钟）：401，抛 REJECTED，没有拿到数据")
    void rejectsExpiredToken() {
        retail.skew = Duration.ofMinutes(2);

        assertThatThrownBy(() -> caller.call(inventory("S001", List.of("S001"))))
                .isInstanceOfSatisfying(ToolCallException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.REJECTED));
        assertThat(retail.served).isEmpty();
    }

    @Test
    @DisplayName("工具名不符：拿 get_store_sales 的令牌去调 get_store_inventory，403，没有返回数据")
    void rejectsTokenForAnotherTool() throws Exception {
        var token = issuer.issue(AUDIENCE, "get_store_sales", "u-42", Map.of("stores", List.of("S001")), "jti-x");
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_store_inventory\","
                + "\"arguments\":{\"store_id\":\"S001\"}}}";

        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(retail.url()))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEmpty();
        assertThat(retail.served).isEmpty();
    }
}
