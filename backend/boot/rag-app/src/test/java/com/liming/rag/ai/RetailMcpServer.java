package com.liming.rag.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模拟新零售客户的 MCP 服务（D-059：只作测试和评测用，不进发布包）。按规范 D-058 做第二道校验：
 * 令牌签名（JWKS）、{@code iss}、{@code aud}、{@code exp}、{@code tool} 与被调用的工具一致，
 * 门店必须在令牌的 {@code data_scope} 内；返回 {@code data/source/query/asOf/queriedAt/truncated}。
 * 两个只读工具：{@code get_store_inventory}（库存）、{@code get_store_sales}（销售）。
 */
final class RetailMcpServer implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();
    private static final Duration TOLERANCE = Duration.ofSeconds(5);

    private static final List<Map<String, Object>> INVENTORY = List.of(
            Map.of("store_id", "S001", "sku", "A1001", "name", "鲜牛奶 1L", "quantity", 24),
            Map.of("store_id", "S001", "sku", "A1002", "name", "酸奶 200g", "quantity", 8),
            Map.of("store_id", "S002", "sku", "A1001", "name", "鲜牛奶 1L", "quantity", 3));
    private static final List<Map<String, Object>> SALES = List.of(
            Map.of("store_id", "S001", "date", "2026-11-06", "amount", 5230),
            Map.of("store_id", "S001", "date", "2026-11-07", "amount", 6120),
            Map.of("store_id", "S002", "date", "2026-11-07", "amount", 4010));

    /** 验签用的公钥（取自我方的 JWKS 地址）。 */
    private final Supplier<Map<String, Object>> jwks;
    /** 期望的受众；测试可改成别的值模拟“不是给自己的令牌”。 */
    volatile String audience;
    /** 本服务的时钟相对真实时间拨快多少，测试用来模拟令牌过期。 */
    volatile Duration skew = Duration.ZERO;
    /** 收到的每个 tools/call 的（令牌 jti，工具名，是否返回了数据），按顺序。 */
    final List<String> served = new CopyOnWriteArrayList<>();

    private final HttpServer server;

    RetailMcpServer(String audience, Supplier<Map<String, Object>> jwks) throws IOException {
        this.audience = audience;
        this.jwks = jwks;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    private Instant now() {
        return Instant.now().plus(skew);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "");
                return;
            }
            var request = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var method = request.path("method").asString();
            var claims = authenticate(exchange);
            if (claims == null) {
                return;
            }
            var id = request.has("id") ? request.get("id").toString() : null;
            switch (method) {
                case "initialize" -> send(exchange, 200, result(id, Map.of("protocolVersion",
                        request.path("params").path("protocolVersion").asString(), "capabilities",
                        Map.of("tools", Map.of()), "serverInfo", Map.of("name", "retail-mock", "version", "1"))));
                case "tools/list" -> send(exchange, 200, result(id, Map.of("tools", tools())));
                case "tools/call" -> call(exchange, id, request.path("params"), claims);
                default -> send(exchange, 202, "");
            }
        } catch (RuntimeException e) {
            send(exchange, 400, "");
        }
    }

    /** 签名、签发者、受众、有效期都对才放行；否则 401（令牌无效）。返回声明，已经应答时返回 null。 */
    private Map<String, Object> authenticate(HttpExchange exchange) throws IOException {
        var header = exchange.getRequestHeaders().getFirst("Authorization");
        try {
            if (header == null || !header.startsWith("Bearer ")) {
                throw new IllegalArgumentException("没有令牌");
            }
            var claims = verify(header.substring("Bearer ".length()));
            var exp = Instant.ofEpochSecond(((Number) claims.get("exp")).longValue());
            if (!"rag-pilot".equals(claims.get("iss")) || !audience.equals(claims.get("aud"))
                    || now().minus(TOLERANCE).isAfter(exp)) {
                throw new IllegalArgumentException("声明不对");
            }
            return claims;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            send(exchange, 401, "");
            return null;
        }
    }

    @SuppressWarnings("unchecked") // JWT 的声明都是 JSON 对象。
    private Map<String, Object> verify(String token) throws java.security.GeneralSecurityException {
        var parts = token.split("\\.");
        var keys = (List<Map<String, Object>>) jwks.get().get("keys");
        var jwk = keys.get(0);
        var parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        var point = new ECPoint(new BigInteger(1, BASE64URL.decode((String) jwk.get("x"))),
                new BigInteger(1, BASE64URL.decode((String) jwk.get("y"))));
        var key = KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(point, parameters.getParameterSpec(ECParameterSpec.class)));
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initVerify(key);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(BASE64URL.decode(parts[2]))) {
            throw new IllegalArgumentException("签名不对");
        }
        return JSON.readValue(BASE64URL.decode(parts[1]), Map.class);
    }

    @SuppressWarnings("unchecked") // data_scope 是 {"stores":[…]}。
    private void call(HttpExchange exchange, String id, JsonNode params, Map<String, Object> claims)
            throws IOException {
        var tool = params.path("name").asString();
        // 令牌只用于签发时指定的工具；拿 A 工具的令牌调 B 工具是 403。
        if (!tool.equals(claims.get("tool"))) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
            send(exchange, 403, "");
            return;
        }
        var arguments = params.path("arguments");
        var store = arguments.path("store_id").asString();
        var scope = (Map<String, Object>) claims.getOrDefault("data_scope", Map.of());
        var stores = (List<String>) scope.getOrDefault("stores", List.of());
        if (!stores.contains(store)) {
            served.add(claims.get("jti") + " " + tool + " out_of_scope");
            Map<String, Object> error = Map.of("code", "out_of_scope", "message", "无权查询门店 " + store + "。");
            send(exchange, 200, result(id, content(error, true)));
            return;
        }
        var rows = new ArrayList<Map<String, Object>>();
        var query = new LinkedHashMap<String, Object>();
        query.put("store_id", store);
        var dataset = switch (tool) {
            case "get_store_inventory" -> {
                var sku = arguments.path("sku").asString();
                query.put("sku", sku);
                INVENTORY.stream().filter(r -> r.get("store_id").equals(store) && (sku.isEmpty() || sku.equals(r.get("sku"))))
                        .forEach(rows::add);
                yield "store_inventory";
            }
            case "get_store_sales" -> {
                SALES.stream().filter(r -> r.get("store_id").equals(store)).forEach(rows::add);
                yield "store_sales";
            }
            default -> null;
        };
        if (dataset == null) {
            send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32602,\"message\":\"Unknown tool\"}}");
            return;
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("data", rows);
        body.put("source", Map.of("system", "ERP", "dataset", dataset));
        body.put("query", query);
        body.put("asOf", now().toString());
        body.put("queriedAt", now().toString());
        body.put("truncated", false);
        served.add(claims.get("jti") + " " + tool + " rows=" + rows.size());
        send(exchange, 200, result(id, content(body, false)));
    }

    private static List<Map<String, Object>> tools() {
        var read = Map.of("readOnlyHint", true);
        var inputSchema = Map.of("type", "object", "properties", Map.of("store_id", Map.of("type", "string")),
                "required", List.of("store_id"), "additionalProperties", true);
        return List.of(
                Map.of("name", "get_store_inventory", "description", "按门店和商品编码查询当前库存。只读。",
                        "inputSchema", inputSchema, "annotations", read),
                Map.of("name", "get_store_sales", "description", "按门店查询每日销售额。只读。",
                        "inputSchema", inputSchema, "annotations", read));
    }

    private static Map<String, Object> content(Map<String, Object> structured, boolean error) {
        return Map.of("content", List.of(Map.of("type", "text", "text", JSON.writeValueAsString(structured))),
                "structuredContent", structured, "isError", error);
    }

    private static String result(String id, Map<String, Object> result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + JSON.writeValueAsString(result) + "}";
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
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
