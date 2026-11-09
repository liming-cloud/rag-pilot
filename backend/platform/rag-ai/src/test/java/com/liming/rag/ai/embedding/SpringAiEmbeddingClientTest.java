package com.liming.rag.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.common.AiProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

            var vectors = new SpringAiEmbeddingClient(properties, registry).embed(List.of("临期商品"));

            assertThat(vectors).hasSize(1);
            assertThat(calls).hasValue(2);
            // 向量化也留下 Spring AI 的观测（#145），由 Actuator 汇总成指标。
            assertThat(observed).contains("gen_ai.client.operation");
        } finally {
            server.stop(0);
        }
    }
}
