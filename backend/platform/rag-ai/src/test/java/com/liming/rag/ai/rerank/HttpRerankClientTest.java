package com.liming.rag.ai.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.ai.common.ModelCallUsage;
import com.liming.rag.ai.common.TestRoutes;
import com.liming.rag.ai.common.TestUsage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 重排（#145 C8）：结果按原顺序放回，服务商出错时给出可读的原因。 */
class HttpRerankClientTest {

    private HttpServer server;
    private final AtomicReference<String> request = new AtomicReference<>();
    private final TestUsage usage = new TestUsage();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("按序号放回原来的顺序，没给分的记 0 分；带上密钥和模型名；没有文档时不调用")
    void scoresInOriginalOrder() throws Exception {
        var client = client(200, "{\"results\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.4}]}");

        assertThat(client.score("冷柜几度", List.of("a", "b", "c"))).containsExactly(0.4, 0.0, 0.9);
        assertThat(request.get()).contains("\"model\":\"bge-reranker\"").contains("\"query\":\"冷柜几度\"");
        assertThat(client.score("冷柜几度", List.of())).isEmpty();
    }

    @Test
    @DisplayName("重排报一次用量，token 取服务商返回的 meta.tokens；服务商没返回时记 0，仍算一次调用（#169）")
    void reportsUsage() throws Exception {
        var client = client(200, "{\"results\":[{\"index\":0,\"relevance_score\":0.4}],"
                + "\"meta\":{\"tokens\":{\"input_tokens\":120,\"output_tokens\":0}}}");
        client.score("q", List.of("a"));
        stop();
        client(200, "{\"results\":[{\"index\":0,\"relevance_score\":0.4}]}").score("q", List.of("a"));

        assertThat(usage.calls).extracting(ModelCallUsage::purpose, ModelCallUsage::model, ModelCallUsage::inputTokens,
                ModelCallUsage::outputTokens).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("RERANK", "bge-reranker", 120L, 0L),
                        org.assertj.core.groups.Tuple.tuple("RERANK", "bge-reranker", 0L, 0L));
    }

    @Test
    @DisplayName("服务商返回错误状态码、越界的序号时抛出 ModelCallException，说明原因")
    void rejectsBadResponses() throws Exception {
        assertThatThrownBy(() -> client(503, "{}").score("q", List.of("a")))
                .isInstanceOf(ModelCallException.class).hasMessageContaining("HTTP 503");
        stop();
        assertThatThrownBy(() -> client(200, "{\"results\":[{\"index\":5,\"relevance_score\":0.9}]}")
                .score("q", List.of("a"))).isInstanceOf(ModelCallException.class).hasMessageContaining("越界");
    }

    @Test
    @DisplayName("连不上服务商时抛出 ModelCallException，提示稍后重试")
    void reportsUnreachableService() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var client = new HttpRerankClient(properties("http://127.0.0.1:" + port + "/v1"),
                TestRoutes.environmentOnly(properties("http://127.0.0.1:" + port + "/v1")), usage.reports());

        assertThatThrownBy(() -> client.score("q", List.of("a"))).isInstanceOf(ModelCallException.class)
                .hasMessageContaining("暂时不可用");
    }

    private HttpRerankClient client(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var properties = properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        return new HttpRerankClient(properties, TestRoutes.of(properties, Map.of()), usage.reports());
    }

    private static AiProperties properties(String baseUrl) {
        return new AiProperties(baseUrl, "rr-key", Duration.ofSeconds(2),
                new AiProperties.Embedding("openai", "BAAI/bge-m3", 16), new AiProperties.Rerank("openai", "bge-reranker"),
                null);
    }
}
