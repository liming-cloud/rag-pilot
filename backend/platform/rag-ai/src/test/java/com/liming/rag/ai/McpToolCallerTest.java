package com.liming.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.ToolCallException.Reason;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 工具调用（D-058）：用桩 MCP 服务验证成功、令牌、超时重试、401、429、工具错误、协议错误。 */
class McpToolCallerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RESULT = "{\"data\":[{\"store_id\":\"S001\",\"quantity\":24}],\"truncated\":false}";

    private static final String TOOLS = "{\"tools\":[{\"name\":\"get_store_inventory\",\"title\":\"门店库存查询\","
            + "\"description\":\"按门店查询库存。只读。\",\"inputSchema\":{\"type\":\"object\",\"properties\":"
            + "{\"store_id\":{\"type\":\"string\"}},\"required\":[\"store_id\"]}}]}";

    private HttpServer server;
    private McpToolCaller caller;
    private ToolTokenIssuer tokens;
    /** 每个 tools/call 请求的处理；第几次请求（从 1 开始）。 */
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile ToolHandler onToolCall;

    @FunctionalInterface
    interface ToolHandler {
        void handle(HttpExchange exchange, Object id, int attempt) throws Exception;
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::route);
        server.start();
        var clock = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZoneOffset.UTC);
        var properties = new ToolProperties(ToolSigningKeys.generateJwk("k1"), "rag-pilot", Duration.ofMillis(800),
                Map.of("retail", new ToolProperties.Endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                        "https://erp.example.com/mcp")));
        tokens = new ToolTokenIssuer(properties, clock);
        caller = new McpToolCaller(properties, tokens, ObservationRegistry.NOOP);
        onToolCall = (exchange, id, attempt) -> reply(exchange, 200, rpcResult(id, structured(RESULT, false)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static ToolCall inventory() {
        return new ToolCall("retail", "get_store_inventory", Map.of("store_id", "S001"), "u-42",
                Map.of("stores", List.of("S001")));
    }

    @Test
    @DisplayName("列出工具：用服务令牌（sub=service:rag-pilot、tool=*、数据范围为空），返回名称、说明和参数结构")
    void listsToolsWithServiceToken() throws Exception {
        var tools = caller.list("retail");

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("get_store_inventory");
            assertThat(tool.title()).isEqualTo("门店库存查询");
            assertThat(tool.description()).isEqualTo("按门店查询库存。只读。");
            assertThat(JSON.readTree(tool.inputSchema()).path("required").get(0).asString()).isEqualTo("store_id");
        });
        assertThat(toolCalls).hasValue(0);
        var jwk = jwk();
        assertThat(authorizations).isNotEmpty().allSatisfy(token -> assertThat(ToolJwt.verify(token, jwk).claims())
                .containsEntry("sub", ToolTokenIssuer.SERVICE_SUBJECT).containsEntry("tool", ToolTokenIssuer.ANY_TOOL)
                .containsEntry("data_scope", Map.of()));
    }

    @Test
    @DisplayName("成功：返回结构化结果；连接用服务令牌，调用用该用户、该工具的令牌，受众是端点的规范地址")
    void callsToolWithPerCallToken() throws Exception {
        var result = caller.call(inventory());

        assertThat(result.content()).containsEntry("truncated", false);
        assertThat(result.content().get("data")).isEqualTo(List.of(Map.of("store_id", "S001", "quantity", 24)));
        assertThat(authorizations).hasSizeGreaterThanOrEqualTo(2);
        var jwk = jwk();
        var service = ToolJwt.verify(authorizations.get(0), jwk).claims();
        assertThat(service).containsEntry("sub", "service:rag-pilot").containsEntry("data_scope", Map.of());
        var user = ToolJwt.verify(authorizations.get(authorizations.size() - 1), jwk).claims();
        assertThat(user).containsEntry("sub", "u-42").containsEntry("tool", "get_store_inventory")
                .containsEntry("aud", "https://erp.example.com/mcp")
                .containsEntry("data_scope", Map.of("stores", List.of("S001")))
                .containsEntry("jti", result.tokenId());
    }

    @Test
    @DisplayName("超时：重试 1 次，第二次成功就返回；两次都超时抛 UNAVAILABLE，一共只请求 2 次")
    void retriesTimeoutOnce() {
        onToolCall = (exchange, id, attempt) -> {
            if (attempt == 1) {
                Thread.sleep(1500);
            }
            reply(exchange, 200, rpcResult(id, structured(RESULT, false)));
        };
        assertThat(caller.call(inventory()).content()).containsKey("data");
        assertThat(toolCalls.get()).isEqualTo(2);

        toolCalls.set(0);
        onToolCall = (exchange, id, attempt) -> Thread.sleep(1500);
        assertThatThrownBy(() -> caller.call(inventory())).isInstanceOfSatisfying(ToolCallException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));
        assertThat(toolCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("503：重试 1 次；401：不重试，抛 REJECTED，不返回数据")
    void retriesServerErrorButNotUnauthorized() {
        onToolCall = (exchange, id, attempt) -> reply(exchange, 503, "");
        assertThatThrownBy(() -> caller.call(inventory())).isInstanceOfSatisfying(ToolCallException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));
        assertThat(toolCalls.get()).isEqualTo(2);

        toolCalls.set(0);
        onToolCall = (exchange, id, attempt) -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            reply(exchange, 401, "");
        };
        assertThatThrownBy(() -> caller.call(inventory())).isInstanceOfSatisfying(ToolCallException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.REJECTED);
            assertThat(e.getMessage()).doesNotContain("127.0.0.1").doesNotContain("Bearer");
        });
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("429：不重试，抛 RATE_LIMITED（对应 TOO_MANY_REQUESTS）")
    void doesNotRetryRateLimit() {
        onToolCall = (exchange, id, attempt) -> {
            exchange.getResponseHeaders().add("Retry-After", "5");
            reply(exchange, 429, "");
        };

        assertThatThrownBy(() -> caller.call(inventory())).isInstanceOfSatisfying(ToolCallException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.RATE_LIMITED);
            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(5));
            assertThat(e.kind()).isEqualTo(com.liming.rag.shared.ErrorKind.TOO_MANY_REQUESTS);
        });
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("isError:true：抛 TOOL_ERROR，带工具给的错误码和说明，不重试")
    void reportsToolError() {
        onToolCall = (exchange, id, attempt) -> reply(exchange, 200, rpcResult(id,
                structured("{\"code\":\"out_of_scope\",\"message\":\"无权查询门店 S002。\"}", true)));

        assertThatThrownBy(() -> caller.call(inventory())).isInstanceOfSatisfying(ToolCallException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.TOOL_ERROR);
            assertThat(e.toolCode()).isEqualTo("out_of_scope");
            assertThat(e.getMessage()).isEqualTo("无权查询门店 S002。");
        });
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("协议错误（未知工具的 JSON-RPC 错误、HTTP 400、结果没有结构化内容）：抛 PROTOCOL，不重试")
    void reportsProtocolErrors() {
        onToolCall = (exchange, id, attempt) -> reply(exchange, 200,
                "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32602,\"message\":\"Unknown tool\"}}");
        assertThatThrownBy(() -> caller.call(inventory())).isInstanceOfSatisfying(ToolCallException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.PROTOCOL));
        assertThat(toolCalls.get()).isEqualTo(1);

        toolCalls.set(0);
        onToolCall = (exchange, id, attempt) -> reply(exchange, 400, "");
        assertThatThrownBy(() -> caller.call(inventory())).isInstanceOfSatisfying(ToolCallException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.PROTOCOL));
        assertThat(toolCalls.get()).isEqualTo(1);

        onToolCall = (exchange, id, attempt) -> reply(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"isError\":false}}");
        assertThatThrownBy(() -> caller.call(inventory())).isInstanceOfSatisfying(ToolCallException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.PROTOCOL));
    }

    @Test
    @DisplayName("没有配置的连接、没有配置签名密钥：抛 NOT_CONFIGURED，不发请求")
    void failsWhenNotConfigured() {
        var unknown = new ToolCall("nowhere", "t", Map.of(), "u", Map.of());
        assertThatThrownBy(() -> caller.call(unknown)).isInstanceOfSatisfying(ToolCallException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));

        var noKey = new ToolProperties("", "rag-pilot", Duration.ofSeconds(1), Map.of("retail",
                new ToolProperties.Endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", null)));
        var unsigned = new McpToolCaller(noKey, new ToolTokenIssuer(noKey, Clock.systemUTC()), ObservationRegistry.NOOP);
        assertThatThrownBy(() -> unsigned.call(inventory())).isInstanceOfSatisfying(ToolCallException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
        assertThatThrownBy(() -> unsigned.list("retail")).isInstanceOfSatisfying(ToolCallException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
        assertThat(authorizations).isEmpty();
    }

    private Map<String, Object> jwk() {
        @SuppressWarnings("unchecked") // JWKS 的结构由 ToolJwks 约定。
        var keys = (List<Map<String, Object>>) tokens.jwks().get("keys");
        return keys.get(0);
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                authorizations.add(authorization.substring("Bearer ".length()));
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                reply(exchange, 405, "");
                return;
            }
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var request = JSON.readTree(body);
            var method = request.path("method").asString();
            var id = request.has("id") ? request.get("id").toString() : null;
            switch (method) {
                case "initialize" -> reply(exchange, 200, rpcResult(id, "{\"protocolVersion\":"
                        + JSON.writeValueAsString(request.path("params").path("protocolVersion").asString())
                        + ",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"stub\",\"version\":\"1\"}}"));
                case "tools/call" -> onToolCall.handle(exchange, id, toolCalls.incrementAndGet());
                case "tools/list" -> reply(exchange, 200, rpcResult(id, TOOLS));
                default -> reply(exchange, 202, "");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // 测试里被中断（客户端已超时关闭连接）时忽略。
            exchange.close();
        }
    }

    private static String structured(String json, boolean error) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(json) + "}],\"structuredContent\":"
                + json + ",\"isError\":" + error + "}";
    }

    private static String rpcResult(Object id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
