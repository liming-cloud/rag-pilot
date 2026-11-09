package com.liming.rag.ai.probe;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.ModelProbe;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.ai.common.ModelRoute;
import com.liming.rag.ai.common.ModelRoutes;
import com.liming.rag.ai.common.ModelUsage;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型管理用的连通测试和模型列表（D-043），直接用 JDK HttpClient 调 OpenAI 兼容接口：对话发一句只要 1 个 token 的
 * 请求，重排送一对短文本，模型列表读 {@code GET /models}。10 秒超时。给管理员看的说明里不出现密钥。
 */
final class OpenAiCompatibleProbe implements ModelProbe {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** 服务商的错误说明最多显示这么多字，避免整页 HTML 出现在界面上。 */
    private static final int MAX_DETAIL = 120;

    private final AiProperties properties;
    private final ModelRoutes routes;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .proxy(ProxySelector.getDefault())
            .build();

    OpenAiCompatibleProbe(AiProperties properties, ModelRoutes routes) {
        this.properties = properties;
        this.routes = routes;
    }

    @Override
    public ModelRoute environment(ModelUsage usage) {
        return routes.environment(usage);
    }

    @Override
    public ModelRoute embedding() {
        return new ModelRoute(properties.baseUrl(), null, properties.embedding().model());
    }

    @Override
    public int embeddingDimensions() {
        return EmbeddingClient.DIMENSIONS;
    }

    @Override
    public ProbeResult probe(ModelUsage usage, ModelRoute route) {
        var body = usage == ModelUsage.RERANK
                ? Map.of("model", route.model(), "query", "连通测试", "documents", List.of("连通测试"), "top_n", 1)
                : Map.of("model", route.model(), "messages", List.of(Map.of("role", "user", "content", "回复：好")),
                        "max_tokens", 1);
        var path = usage == ModelUsage.RERANK ? "/rerank" : "/chat/completions";
        long started = System.nanoTime();
        try {
            var response = send(request(route.baseUrl() + path, route.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
            long latency = (System.nanoTime() - started) / 1_000_000;
            if (response.statusCode() / 100 == 2) {
                return new ProbeResult(true, latency, "连通正常");
            }
            return new ProbeResult(false, latency, rejected(response));
        } catch (ModelCallException e) {
            return new ProbeResult(false, null, e.getMessage());
        }
    }

    @Override
    public List<String> models(String baseUrl, String apiKey) {
        var response = send(request(new ModelRoute(baseUrl, apiKey, null).baseUrl() + "/models", apiKey).GET());
        if (response.statusCode() / 100 != 2) {
            throw new ModelCallException("这个服务商没有提供模型列表，请手动填写模型名称");
        }
        var names = new ArrayList<String>();
        for (var item : json.readTree(response.body()).path("data")) {
            var id = item.path("id").asString("");
            if (!id.isBlank()) {
                names.add(id);
            }
        }
        if (names.isEmpty()) {
            throw new ModelCallException("这个服务商没有提供模型列表，请手动填写模型名称");
        }
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private HttpRequest.Builder request(String url, String apiKey) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ModelCallException("地址格式不对，应为 https://域名/v1 这样的形式");
        }
        var builder = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ModelCallException("连接超时（" + TIMEOUT.toSeconds() + " 秒），请检查地址和网络", e);
        } catch (IOException e) {
            throw new ModelCallException("连不上这个服务商，请检查地址和网络", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("测试被中断", e);
        } catch (IllegalArgumentException e) {
            throw new ModelCallException("地址格式不对，应为 https://域名/v1 这样的形式", e);
        }
    }

    /** 服务商拒绝时的说明：状态码加上它返回的错误信息（截断），常见的认证失败单独说明。 */
    private String rejected(HttpResponse<String> response) {
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return "服务商返回 " + response.statusCode() + "：密钥不对或没有权限";
        }
        String detail;
        try {
            var tree = json.readTree(response.body());
            detail = tree.path("error").path("message").asString(tree.path("message").asString(""));
        } catch (RuntimeException e) {
            detail = "";
        }
        detail = detail.strip();
        if (detail.length() > MAX_DETAIL) {
            detail = detail.substring(0, MAX_DETAIL) + "…";
        }
        return "服务商返回 " + response.statusCode() + (detail.isEmpty() ? "" : "：" + detail);
    }
}
