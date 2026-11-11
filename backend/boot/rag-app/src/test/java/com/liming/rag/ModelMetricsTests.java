package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 模型调用指标（#145 V4）：对话模型接到本地假的 OpenAI 兼容服务，问答之后 /actuator/metrics 里有 Spring AI 记下的调用耗时
 * （按操作类型和模型名，各用途在配置里各用一个模型）和 token 用量；指标名和标签里没有提示词、资料和回答的原文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ModelMetricsTests {

    private static final String QUESTION = "收银员交班时备用金怎么处理";
    private static final String DOCUMENT = "收银员交班时要清点备用金，在交班表上签字后再离开收银台。";
    private static final String ANSWER = "交班时要清点备用金并在交班表上签字[1]。";
    private static final HttpServer FAKE = startFake();

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void chatModel(DynamicPropertyRegistry registry) {
        registry.add("rag.ai.base-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort() + "/v1");
        registry.add("rag.ai.api-key", () -> "sk-metrics-test-0000");
        registry.add("rag.ai.chat.provider", () -> "openai");
        registry.add("rag.ai.chat.model", () -> "metrics-answer-model");
        registry.add("rag.ai.chat.light-model", () -> "metrics-light-model");
    }

    @AfterAll
    static void stopFake() {
        FAKE.stop(0);
    }

    @Test
    @DisplayName("问答之后能看到 Spring AI 的模型调用耗时（按操作类型、模型名）和 token 用量，不含提示词和回答原文")
    @SuppressWarnings("unchecked")
    void exposesModelCallMetrics() throws InterruptedException {
        TestLogin.asAdmin(http);
        long kb = createKnowledgeBase();
        var uploaded = upload(kb);
        assertThat(TestDocuments.awaitProcessed(http, kb, uploaded, null)).containsEntry("status", "READY");

        var answer = TestAsk.ask(http, null, List.of(kb), QUESTION);
        assertThat(answer).containsEntry("refused", false);

        var operation = awaitMetric("gen_ai.client.operation");
        var tags = tagValues(operation);
        assertThat(tags.get("gen_ai.operation.name")).contains("chat");
        assertThat(tags.get("gen_ai.request.model")).contains("metrics-answer-model");
        assertThat((List<Map<String, Object>>) operation.get("measurements"))
                .anySatisfy(m -> assertThat(m).containsEntry("statistic", "COUNT"));

        var tokens = awaitMetric("gen_ai.client.token.usage");
        assertThat(tagValues(tokens).get("gen_ai.token.type")).contains("input", "output", "total");
        assertThat(tagValues(tokens).get("gen_ai.request.model")).contains("metrics-answer-model");

        var names = (List<String>) http.getForEntity("/actuator/metrics", Map.class).getBody().get("names");
        var everything = new StringBuilder(String.join(" ", names));
        for (var name : names.stream().filter(n -> n.startsWith("gen_ai") || n.startsWith("spring.ai")).toList()) {
            everything.append(' ').append(http.getForEntity("/actuator/metrics/" + name, Map.class).getBody());
        }
        assertThat(everything.toString()).as("指标里不能有原文").doesNotContain(QUESTION, "备用金", ANSWER, "交班表");
    }

    /** 流式回答的观测在流结束后由另一个线程记下，等它出现（最多 10 秒）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitMetric(String name) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            var response = http.getForEntity("/actuator/metrics/" + name, Map.class);
            if (response.getStatusCode() == HttpStatus.OK || System.nanoTime() > deadline) {
                assertThat(response.getStatusCode()).as("应有指标 " + name).isEqualTo(HttpStatus.OK);
                return response.getBody();
            }
            Thread.sleep(100);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> tagValues(Map<String, Object> metric) {
        var tags = (List<Map<String, Object>>) metric.get("availableTags");
        return tags.stream().collect(java.util.stream.Collectors.toMap(tag -> (String) tag.get("tag"),
                tag -> (List<String>) tag.get("values")));
    }

    private long createKnowledgeBase() {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", "指标知识库"), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> upload(long kb) {
        var file = new ByteArrayResource(DOCUMENT.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "收银规范.txt";
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/knowledge-bases/{id}/documents", new HttpEntity<>(body, headers), Map.class,
                kb).getBody();
    }

    /** 假的对话服务：流式和非流式都回同一句话，并带上 token 用量。 */
    private static HttpServer startFake() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var model = request.contains("metrics-light-model") ? "metrics-light-model" : "metrics-answer-model";
                if (request.matches("(?s).*\"stream\"\\s*:\\s*true.*")) {
                    respondStream(exchange, model);
                } else {
                    respond(exchange, "application/json", "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
                            + "\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"交班备用金\"},\"finish_reason\":\"stop\"}]," + usage() + "}");
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respondStream(HttpExchange exchange, String model) throws IOException {
        var chunk = "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"" + model + "\",";
        respond(exchange, "text/event-stream", "data: " + chunk + "\"choices\":[{\"index\":0,\"delta\":{\"role\":"
                + "\"assistant\",\"content\":\"" + ANSWER + "\"},\"finish_reason\":null}]}\n\n"
                + "data: " + chunk + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: " + chunk + "\"choices\":[]," + usage() + "}\n\n"
                + "data: [DONE]\n\n");
    }

    private static String usage() {
        return "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":18,\"total_tokens\":138}";
    }

    private static void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        try (exchange) {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
