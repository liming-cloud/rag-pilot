package com.liming.rag.ai;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * 调用硅基流动等服务的 {@code POST /rerank} 接口（Jina/Cohere 风格：query + documents，返回 index 与
 * relevance_score）。Spring AI 没有重排模型抽象，这里用 JDK HttpClient 直接调用。
 */
final class HttpRerankClient implements RerankClient {

    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiProperties properties;
    private final ModelRoutes routes;

    HttpRerankClient(AiProperties properties, ModelRoutes routes) {
        properties.requireApiKey();
        this.properties = properties;
        this.routes = routes;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .proxy(ProxySelector.getDefault())
                .build();
    }

    @Override
    public List<Double> score(String query, List<String> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        // 每次按当前配置取服务商和模型，管理端保存后下一次请求就生效（D-043）。
        var route = routes.resolve(ModelUsage.RERANK);
        var response = send(request(route, query, documents));
        if (response.statusCode() / 100 != 2) {
            throw new ModelCallException("重排失败：模型服务返回 HTTP " + response.statusCode());
        }
        return scores(response.body(), documents.size());
    }

    private HttpRequest request(ModelRoute route, String query, List<String> documents) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", route.model());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        body.put("return_documents", false);
        var builder = HttpRequest.newBuilder(URI.create(route.baseUrl() + "/rerank"))
                .timeout(properties.timeout())
                .header("Content-Type", "application/json");
        if (route.apiKey() != null) {
            builder.header("Authorization", "Bearer " + route.apiKey());
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new ModelCallException("重排失败：重排模型服务暂时不可用，请稍后重试", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("重排被中断", e);
        }
    }

    /** 按序号放回原来的顺序；没给分的文档记 0 分。 */
    private List<Double> scores(String body, int size) {
        var scores = new ArrayList<>(Collections.nCopies(size, 0.0));
        for (var result : json.readTree(body).path("results")) {
            int index = result.path("index").asInt(-1);
            if (index < 0 || index >= size) {
                throw new ModelCallException("重排失败：模型返回了越界的序号");
            }
            scores.set(index, result.path("relevance_score").asDouble());
        }
        return scores;
    }
}
