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
    private final URI endpoint;
    private final String apiKey;
    private final AiProperties properties;

    HttpRerankClient(AiProperties properties) {
        this.properties = properties;
        this.apiKey = properties.requireApiKey();
        this.endpoint = URI.create(properties.baseUrl().replaceAll("/+$", "") + "/rerank");
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
        var body = new LinkedHashMap<String, Object>();
        body.put("model", properties.rerank().model());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        body.put("return_documents", false);
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new ModelCallException("重排失败：重排模型服务暂时不可用，请稍后重试", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("重排被中断", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ModelCallException("重排失败：模型服务返回 HTTP " + response.statusCode());
        }
        var scores = new ArrayList<>(Collections.nCopies(documents.size(), 0.0));
        for (var result : json.readTree(response.body()).path("results")) {
            int index = result.path("index").asInt(-1);
            if (index < 0 || index >= documents.size()) {
                throw new ModelCallException("重排失败：模型返回了越界的序号");
            }
            scores.set(index, result.path("relevance_score").asDouble());
        }
        return scores;
    }
}
