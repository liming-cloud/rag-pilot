package com.liming.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpringAiQueryRewritingTest {

    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("规则作系统提示词、原问题作用户消息发给回答模型，输出限一句话（最多 64 个 token，遇换行停）（#145）")
    void rewritesWithCallerInstructions() throws Exception {
        start(200, "临期商品打几折？");

        var rewritten = rewriting().rewrite("你负责把员工的问题改写成规范说法。\n规则：\n1. 纠正错别字。", "临期的东西打几者");

        assertThat(rewritten).isEqualTo("临期商品打几折？");
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst()).contains("\"role\":\"system\"", "你负责把员工的问题改写成规范说法", "纠正错别字",
                "\"content\":\"临期的东西打几者\"",
                "\"max_tokens\":64", "\"stop\":\"\\n\"", "\"model\":\"m\"");
    }

    @Test
    @DisplayName("规则里的花括号原样发给模型，不当作模板占位符")
    void keepsBracesInInstructions() throws Exception {
        start(200, "改写");

        rewriting().rewrite("规则：把 {简称} 改成全称", "问题");

        assertThat(bodies.getFirst()).contains("把 {简称} 改成全称");
    }

    @Test
    @DisplayName("模型不可用时抛 ModelCallException，由调用方按没改写处理")
    void failsWhenModelUnavailable() throws Exception {
        start(503, "");

        assertThatThrownBy(() -> rewriting().rewrite("规则", "问题")).isInstanceOf(ModelCallException.class);
    }

    private QueryRewriting rewriting() {
        var properties = new AiProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key",
                Duration.ofSeconds(5), null, null, new AiProperties.Chat("openai", "m", 0.1, 256, null, null, null));
        return new SpringAiQueryRewriting(new OpenAiChatModels(properties, ObservationRegistry.NOOP),
                TestRoutes.of(properties, Map.of()));
    }

    private void start(int status, String text) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var body = (status == 200
                        ? "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
                                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}]}"
                        : "{\"error\":{\"message\":\"busy\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }
}
