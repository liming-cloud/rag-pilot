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
        start(call -> call == 1 ? new Reply(3000, 200, "慢的回答") : new Reply(0, 200, "快的回答"));
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
        start(call -> call == 1 ? new Reply(3000, 200, "慢的回答") : new Reply(0, 200, "备用回答"));
        var gateway = gateway(Duration.ofMillis(300), "backup-model");

        assertThat(gateway.stream("系统", "问题", delta -> {
        })).isEqualTo("备用回答");
        assertThat(models).containsExactly("m", "backup-model");
    }

    @Test
    @DisplayName("首字及时到达时不发备用请求")
    void noHedgeWhenFast() throws Exception {
        start(call -> new Reply(0, 200, "回答"));
        var gateway = gateway(Duration.ofMillis(1000));

        assertThat(gateway.stream("系统", "问题", delta -> {
        })).isEqualTo("回答");
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("第一个请求失败时由备用请求给出回答")
    void hedgeCoversFailedRequest() throws Exception {
        start(call -> call == 1 ? new Reply(0, 503, null) : new Reply(0, 200, "回答"));
        var gateway = gateway(Duration.ofMillis(200));

        assertThat(gateway.stream("系统", "问题", delta -> {
        })).isEqualTo("回答");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("不配置备用请求时只调用一次")
    void hedgeDisabled() throws Exception {
        start(call -> new Reply(500, 200, "回答"));
        var gateway = gateway(null);

        assertThat(gateway.stream("系统", "问题", delta -> {
        })).isEqualTo("回答");
        assertThat(calls).hasValue(1);
    }

    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    private record Reply(long delayMillis, int status, String text) {
    }

    private void start(java.util.function.IntFunction<Reply> replies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions", exchange -> {
            var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var model = MODEL.matcher(request);
            models.add(model.find() ? model.group(1) : "");
            respond(exchange, replies.apply(calls.incrementAndGet()));
        });
        server.start();
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
        var properties = new AiProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key",
                Duration.ofSeconds(10), null, null,
                new AiProperties.Chat("openai", "m", 0.1, 256, hedgeAfter, hedgeModel));
        return new SpringAiChatGateway(properties);
    }
}
