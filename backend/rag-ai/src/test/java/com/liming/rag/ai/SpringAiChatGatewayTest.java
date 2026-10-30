package com.liming.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpringAiChatGatewayTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> models = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("模型迟迟不出首字时发一个备用请求，用先出字的那个，慢的那个取消")
    void hedgesSlowFirstToken() throws Exception {
        start((call, model) -> call == 1 ? new Reply(3000, 200, "慢的回答") : new Reply(0, 200, "快的回答"));
        var gateway = gateway(Duration.ofMillis(300));
        var deltas = Collections.synchronizedList(new ArrayList<String>());

        long t0 = System.nanoTime();
        var text = gateway.stream("系统", "问题", deltas::add);
        long millis = (System.nanoTime() - t0) / 1_000_000;

        assertThat(text).isEqualTo("快的回答");
        assertThat(String.join("", deltas)).isEqualTo("快的回答");
        assertThat(calls).hasValue(2);
        assertThat(millis).isLessThan(2000);
    }

    @Test
    @DisplayName("配置了备用模型时，备用请求改用它：原模型整体卡住时换一个模型更可能及时出字")
    void hedgesToBackupModel() throws Exception {
        // 按模型名回复，不按到达顺序：CI 上原请求可能晚于备用请求到达（#77）。
        start((call, model) -> "m".equals(model) ? new Reply(3000, 200, "慢的回答") : new Reply(0, 200, "备用回答"));
        var gateway = gateway(Duration.ofMillis(300), "backup-model");

        assertThat(gateway.stream("系统", "问题", delta -> {
        })).isEqualTo("备用回答");
        assertThat(awaitModels(2)).containsExactlyInAnyOrder("m", "backup-model");
    }

    @Test
    @DisplayName("首字及时到达时不发备用请求")
    void noHedgeWhenFast() throws Exception {
        start((call, model) -> new Reply(0, 200, "回答"));
        var gateway = gateway(Duration.ofMillis(1000));

        assertThat(gateway.stream("系统", "问题", delta -> {
        })).isEqualTo("回答");
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("第一个请求失败时由备用请求给出回答")
    void hedgeCoversFailedRequest() throws Exception {
        start((call, model) -> call == 1 ? new Reply(0, 503, null) : new Reply(0, 200, "回答"));
        var gateway = gateway(Duration.ofMillis(200));

        assertThat(gateway.stream("系统", "问题", delta -> {
        })).isEqualTo("回答");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("不配置备用请求时只调用一次")
    void hedgeDisabled() throws Exception {
        start((call, model) -> new Reply(500, 200, "回答"));
        var gateway = gateway(null);

        assertThat(gateway.stream("系统", "问题", delta -> {
        })).isEqualTo("回答");
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("停止信号一到就结束生成，首字还没来也不再等（D-036 停止生成）")
    void stopsWhenSignalled() throws Exception {
        start((call, model) -> new Reply(3000, 200, "慢的回答"));
        var gateway = gateway(null);
        var stop = new CompletableFuture<Void>();
        var deltas = Collections.synchronizedList(new ArrayList<String>());
        Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stop.complete(null);
        });

        long t0 = System.nanoTime();
        var text = gateway.stream("系统", "问题", deltas::add, stop);
        long millis = (System.nanoTime() - t0) / 1_000_000;

        assertThat(text).isEmpty();
        assertThat(deltas).isEmpty();
        assertThat(millis).isLessThan(2000);
    }

    @Test
    @DisplayName("轻量任务（如会话标题）用单独配置的模型，其他调用用回答模型")
    void lightTierUsesLightModel() throws Exception {
        start((call, model) -> new Reply(0, 200, "标题"));
        var gateway = gateway(null, null, "light-m");

        assertThat(gateway.complete("系统", "问题", new ChatGateway.Limits(32, List.of("\n"), ChatGateway.Tier.LIGHT)))
                .isEqualTo("标题");
        assertThat(gateway.complete("系统", "问题", new ChatGateway.Limits(32, List.of("\n")))).isEqualTo("标题");
        assertThat(awaitModels(2)).containsExactly("light-m", "m");
    }

    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    private record Reply(long delayMillis, int status, String text) {
    }

    /** 假的对话服务；{@code replies} 按第几个到达和请求里的模型名决定回复。 */
    private void start(java.util.function.BiFunction<Integer, String, Reply> replies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions", exchange -> {
            var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var matcher = MODEL.matcher(request);
            var model = matcher.find() ? matcher.group(1) : "";
            models.add(model);
            var reply = replies.apply(calls.incrementAndGet(), model);
            if (request.matches("(?s).*\"stream\"\\s*:\\s*true.*")) {
                respond(exchange, reply);
            } else {
                respondWhole(exchange, reply);
            }
        });
        server.start();
    }

    /** 等到假服务收到 {@code count} 个请求（最多 2 秒）；被取消的原请求可能在回答结束后才到达。 */
    private List<String> awaitModels(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (models.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return List.copyOf(models);
    }

    /** 非流式调用（complete）：一次返回完整的 JSON。 */
    private static void respondWhole(HttpExchange exchange, Reply reply) throws IOException {
        try (exchange) {
            var body = ("{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"" + reply.text() + "\"},\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static void respond(HttpExchange exchange, Reply reply) throws IOException {
        try (exchange) {
            if (reply.status() != 200) {
                var body = "{\"error\":{\"message\":\"busy\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            // 像真实服务一样先回响应头，首字迟迟不来。
            out.flush();
            Thread.sleep(reply.delayMillis());
            for (var piece : List.of(reply.text().substring(0, 2), reply.text().substring(2))) {
                out.write(("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + piece
                        + "\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 被取消的请求，客户端已断开。
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private SpringAiChatGateway gateway(Duration hedgeAfter) {
        return gateway(hedgeAfter, null);
    }

    private SpringAiChatGateway gateway(Duration hedgeAfter, String hedgeModel) {
        return gateway(hedgeAfter, hedgeModel, null);
    }

    private SpringAiChatGateway gateway(Duration hedgeAfter, String hedgeModel, String lightModel) {
        var properties = new AiProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key",
                Duration.ofSeconds(10), null, null,
                new AiProperties.Chat("openai", "m", 0.1, 256, hedgeAfter, hedgeModel, lightModel));
        return new SpringAiChatGateway(properties);
    }
}
