package com.liming.rag.ai.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.TextGeneration;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelRoute;
import com.liming.rag.ai.common.ModelUsage;
import com.liming.rag.ai.common.OpenAiChatModels;
import com.liming.rag.ai.common.TestRoutes;
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

class SpringAiTextGenerationTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> models = Collections.synchronizedList(new ArrayList<>());
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final java.util.Map<ModelUsage, ModelRoute> overrides = new java.util.concurrent.ConcurrentHashMap<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("管理端配置了回答和轻量任务的服务商和模型后，下一次请求就打到新地址、用新模型（D-043）")
    void followsConfiguredRoutes() throws Exception {
        start((call, model) -> new Reply(0, 200, "回答"));
        var gateway = gateway(null);

        gateway.complete("系统", "问题");
        overrides.put(ModelUsage.ANSWER, databaseRoute("db-answer"));
        overrides.put(ModelUsage.LIGHT, databaseRoute("db-light"));
        gateway.stream("系统", "问题", delta -> {
        });
        gateway.complete("系统", "标题", new TextGeneration.Limits(16, List.of(), TextGeneration.Tier.LIGHT));

        assertThat(awaitModels(3)).containsExactly("m", "db-answer", "db-light");
        assertThat(paths).containsExactly("/v1/chat/completions", "/db/v1/chat/completions",
                "/db/v1/chat/completions");
    }

    @Test
    @DisplayName("备用回答可以配到另一个服务商")
    void hedgesToConfiguredProvider() throws Exception {
        start((call, model) -> "m".equals(model) ? new Reply(3000, 200, "慢的回答") : new Reply(0, 200, "备用回答"));
        overrides.put(ModelUsage.HEDGE, databaseRoute("db-hedge"));
        var gateway = gateway(Duration.ofMillis(300));

        assertThat(gateway.stream("系统", "问题", delta -> {
        })).isEqualTo("备用回答");
        assertThat(awaitModels(2)).containsExactlyInAnyOrder("m", "db-hedge");
        assertThat(paths).contains("/db/v1/chat/completions");
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
    @DisplayName("正常生成完时停止信号不会被触发：调用方据此判断这一轮是不是员工停的")
    void normalCompletionLeavesStopUntouched() throws Exception {
        start((call, model) -> new Reply(0, 200, "完整回答"));
        var gateway = gateway(null);
        var stop = new CompletableFuture<Void>();

        assertThat(gateway.stream("系统", "问题", delta -> {
        }, stop)).isEqualTo("完整回答");
        assertThat(stop).isNotDone();
    }

    @Test
    @DisplayName("轻量任务（如会话标题）用单独配置的模型，其他调用用回答模型")
    void lightTierUsesLightModel() throws Exception {
        start((call, model) -> new Reply(0, 200, "标题"));
        var gateway = gateway(null, null, "light-m");

        assertThat(gateway.complete("系统", "问题", new TextGeneration.Limits(32, List.of("\n"), TextGeneration.Tier.LIGHT)))
                .isEqualTo("标题");
        assertThat(gateway.complete("系统", "问题", new TextGeneration.Limits(32, List.of("\n")))).isEqualTo("标题");
        assertThat(awaitModels(2)).containsExactly("light-m", "m");
    }

    @Test
    @DisplayName("每次调用都留下 Spring AI 的观测：ChatClient 一层、模型调用一层（带模型名），供 /actuator/metrics 统计（#145）")
    void recordsSpringAiObservations() throws Exception {
        start((call, model) -> new Reply(0, 200, "回答"));
        var names = Collections.synchronizedList(new ArrayList<String>());
        var registry = io.micrometer.observation.ObservationRegistry.create();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
            @Override
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(io.micrometer.observation.Observation.Context context) {
                names.add(context.getName() + " " + context.getLowCardinalityKeyValues().stream()
                        .filter(keyValue -> keyValue.getKey().equals("gen_ai.request.model"))
                        .map(io.micrometer.common.KeyValue::getValue).findFirst().orElse(""));
            }
        });
        var generation = gateway(null, null, null, registry);

        generation.complete("系统", "问题", new TextGeneration.Limits(16, List.of()));
        generation.stream("系统", "问题", delta -> {
        });

        // 流式调用的观测在流结束后由另一个线程结束，等它记下来再取快照，不在写入时遍历。
        List<String> recorded = List.of();
        for (int i = 0; i < 100; i++) {
            synchronized (names) {
                recorded = List.copyOf(names);
            }
            if (recorded.stream().filter(name -> name.startsWith("gen_ai.client.operation")).count() >= 2) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(recorded).contains("gen_ai.client.operation m", "spring.ai.chat.client ");
        assertThat(recorded.stream().filter(name -> name.startsWith("gen_ai.client.operation"))).hasSize(2);
    }

    @Test
    @DisplayName("提示词和资料里的花括号、尖括号原样发给模型，不当作模板占位符（资料原文、员工的问题都可能带）")
    void sendsBracesVerbatim() throws Exception {
        start((call, model) -> new Reply(0, 200, "回答"));
        var generation = gateway(null);

        generation.complete("规则 {role} <x>", "资料：价格 {原价} 与 {} 和 }{", new TextGeneration.Limits(16, List.of()));
        generation.stream("规则 {role}", "资料 {a}", delta -> {
        });

        assertThat(bodies).hasSize(2);
        assertThat(bodies.getFirst()).contains("规则 {role} <x>", "资料：价格 {原价} 与 {} 和 }{");
        assertThat(bodies.get(1)).contains("规则 {role}", "资料 {a}");
    }

    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    private record Reply(long delayMillis, int status, String text) {
    }

    /** 假的对话服务；{@code replies} 按第几个到达和请求里的模型名决定回复。 */
    private void start(java.util.function.BiFunction<Integer, String, Reply> replies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(request);
            var matcher = MODEL.matcher(request);
            var model = matcher.find() ? matcher.group(1) : "";
            models.add(model);
            var reply = replies.apply(calls.incrementAndGet(), model);
            if (request.matches("(?s).*\"stream\"\\s*:\\s*true.*")) {
                respond(exchange, reply);
            } else {
                respondWhole(exchange, reply);
            }
        };
        // 环境变量里的服务商在 /v1，界面上配的另一个服务商在 /db/v1。
        server.createContext("/v1/chat/completions", handler);
        server.createContext("/db/v1/chat/completions", handler);
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
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"" + reply.text().replace("\"", "\\\"") + "\"},\"finish_reason\":\"stop\"}]}")
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

    private SpringAiTextGeneration gateway(Duration hedgeAfter) {
        return gateway(hedgeAfter, null);
    }

    private SpringAiTextGeneration gateway(Duration hedgeAfter, String hedgeModel) {
        return gateway(hedgeAfter, hedgeModel, null);
    }

    private SpringAiTextGeneration gateway(Duration hedgeAfter, String hedgeModel, String lightModel) {
        return gateway(hedgeAfter, hedgeModel, lightModel, io.micrometer.observation.ObservationRegistry.NOOP);
    }

    private SpringAiTextGeneration gateway(Duration hedgeAfter, String hedgeModel, String lightModel,
            io.micrometer.observation.ObservationRegistry registry) {
        var properties = new AiProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key",
                Duration.ofSeconds(10), null, null,
                new AiProperties.Chat("openai", "m", 0.1, 256, hedgeAfter, hedgeModel, lightModel));
        return new SpringAiTextGeneration(properties, new OpenAiChatModels(properties, registry),
                TestRoutes.of(properties, overrides));
    }

    private ModelRoute databaseRoute(String model) {
        return new ModelRoute("http://127.0.0.1:" + server.getAddress().getPort() + "/db/v1", null, model);
    }
}
