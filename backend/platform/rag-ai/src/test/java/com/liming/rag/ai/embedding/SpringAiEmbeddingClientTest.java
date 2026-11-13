package com.liming.rag.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.ai.common.TestUsage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpringAiEmbeddingClientTest {

    @Test
    @DisplayName("嵌入接口被限流时自动重试，入库不因一次 429 失败")
    void retriesWhenRateLimited() throws Exception {
        var calls = new AtomicInteger();
        var vector = String.join(",", Collections.nCopies(EmbeddingClient.DIMENSIONS, "0.01"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body;
            if (calls.incrementAndGet() == 1) {
                body = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("retry-after-ms", "10");
                exchange.sendResponseHeaders(429, body.length);
            } else {
                body = ("{\"object\":\"list\",\"model\":\"BAAI/bge-m3\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                        + "\"embedding\":[" + vector + "]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
            }
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var properties = new AiProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key",
                    Duration.ofSeconds(5), new AiProperties.Embedding("openai", "BAAI/bge-m3", 16), null, null);

            var observed = Collections.synchronizedList(new java.util.ArrayList<String>());
            var registry = io.micrometer.observation.ObservationRegistry.create();
            registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
                @Override
                public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                    return true;
                }

                @Override
                public void onStop(io.micrometer.observation.Observation.Context context) {
                    observed.add(context.getName());
                }
            });

            var usage = new TestUsage();
            var vectors = new SpringAiEmbeddingClient(properties, registry, usage.reports()).embed(List.of("临期商品"));

            assertThat(vectors).hasSize(1);
            assertThat(calls).hasValue(2);
            // 入库向量化的用量（#169）：嵌入只有输入 token；被限流重试的失败请求不计。
            assertThat(usage.calls).containsExactly(new com.liming.rag.ai.common.ModelCallUsage("EMBEDDING",
                    properties.baseUrl(), "BAAI/bge-m3", 1, 0));
            // 向量化也留下 Spring AI 的观测（#145），由 Actuator 汇总成指标。
            assertThat(observed).contains("gen_ai.client.operation");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("大文档入库一直被限流时按批退避续传：只重做被限流的那一批，最终全部算完（#226）")
    void resumesBatchAfterRepeatedRateLimits() throws Exception {
        // 第 2 个请求起连续 6 次 429，超过 SDK 自己的 3 次重试，第三轮退避后放行。
        var server = new FakeEmbeddingServer(call -> call >= 2 && call <= 7);
        try {
            var client = server.client(List.of(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));

            var vectors = client.embedForIngestion(List.of("第一批", "第二批", "第三批"));

            assertThat(vectors).hasSize(3);
            // 已算好的批次不重做。
            assertThat(server.requests.get("第一批")).hasValue(1);
            assertThat(server.requests.get("第三批")).hasValue(1);
            assertThat(server.requests.get("第二批")).hasValue(7);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("退避用完仍被限流时照常失败，文档按“模型暂时不可用”记下")
    void failsWhenRateLimitPersists() throws Exception {
        var server = new FakeEmbeddingServer(call -> true);
        try {
            var client = server.client(List.of(Duration.ofMillis(1)));

            assertThatThrownBy(() -> client.embedForIngestion(List.of("临期商品"))).isInstanceOf(ModelCallException.class);
            // SDK 每轮 1 + 3 次，退避一次后再来一轮。
            assertThat(server.requests.get("临期商品")).hasValue(8);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("在线请求（问题向量、知识缺口归类）被限流时不做入库那样的长退避")
    void onlineCallsDoNotWaitForRateLimitBackoff() throws Exception {
        var server = new FakeEmbeddingServer(call -> true);
        try {
            var client = server.client(List.of(Duration.ofMillis(1)));

            assertThatThrownBy(() -> client.embedQuestion("临期商品")).isInstanceOf(ModelCallException.class);
            assertThatThrownBy(() -> client.embed(List.of("临期商品"))).isInstanceOf(ModelCallException.class);
            assertThat(server.requests.get("临期商品")).hasValue(2 * (1 + SpringAiEmbeddingClient.MAX_RETRIES));
        } finally {
            server.stop();
        }
    }

    /** 模拟 OpenAI 兼容的嵌入接口：按请求序号决定是否返回 429，并按输入文字计数。每次请求一条文字。 */
    private static final class FakeEmbeddingServer {

        private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final HttpServer server;

        FakeEmbeddingServer(IntPredicate rateLimited) throws Exception {
            var vector = String.join(",", Collections.nCopies(EmbeddingClient.DIMENSIONS, "0.01"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/embeddings", exchange -> {
                var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var input = request.replaceAll("(?s).*\"input\"\\s*:\\s*\\[\\s*\"([^\"]*)\".*", "$1");
                requests.computeIfAbsent(input, key -> new AtomicInteger()).incrementAndGet();
                byte[] body;
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (rateLimited.test(calls.incrementAndGet())) {
                    body = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("retry-after-ms", "1");
                    exchange.sendResponseHeaders(429, body.length);
                } else {
                    body = ("{\"object\":\"list\",\"model\":\"BAAI/bge-m3\",\"data\":[{\"object\":\"embedding\","
                            + "\"index\":0,\"embedding\":[" + vector + "]}],\"usage\":{\"prompt_tokens\":1,"
                            + "\"total_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                }
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        SpringAiEmbeddingClient client(List<Duration> rateLimitWaits) {
            var properties = new AiProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key",
                    Duration.ofSeconds(5), new AiProperties.Embedding("openai", "BAAI/bge-m3", 1), null, null);
            return new SpringAiEmbeddingClient(properties, io.micrometer.observation.ObservationRegistry.NOOP,
                    new TestUsage().reports(), rateLimitWaits);
        }

        void stop() {
            server.stop(0);
        }
    }
}
