package com.liming.rag.ai.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.ai.common.ModelRoute;
import com.liming.rag.ai.common.ModelUsage;
import com.liming.rag.ai.common.TestRoutes;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 连通测试和模型列表（D-043），对着本地的假服务商。 */
class OpenAiCompatibleProbeTest {

    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private OpenAiCompatibleProbe probe;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reply("/ok/v1/chat/completions", 200, "{\"choices\":[{\"message\":{\"content\":\"好\"}}]}");
        reply("/ok/v1/rerank", 200, "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}");
        reply("/ok/v1/models", 200, "{\"data\":[{\"id\":\"b-model\"},{\"id\":\"A-model\"}]}");
        reply("/missing/v1/chat/completions", 404, "{\"error\":{\"message\":\"Model does not exist.\"}}");
        reply("/missing/v1/models", 404, "not found");
        reply("/denied/v1/chat/completions", 401, "{\"error\":{\"message\":\"Invalid token sk-secret\"}}");
        reply("/broken/v1/chat/completions", 500, "<html>Internal Server Error</html>");
        reply("/verbose/v1/chat/completions", 400, "{\"message\":\"" + "长".repeat(500) + "\"}");
        reply("/empty/v1/models", 200, "{\"data\":[{\"id\":\"\"}]}");
        server.start();
        var properties = new AiProperties(url("ok"), "env-key", Duration.ofSeconds(10),
                new AiProperties.Embedding("openai", "BAAI/bge-m3", 16), new AiProperties.Rerank("openai", "rr"),
                new AiProperties.Chat("openai", "env-chat", 0.1, 256, null, null, "env-light"));
        probe = new OpenAiCompatibleProbe(properties, TestRoutes.environmentOnly(properties));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("对话发一句只要 1 个 token 的请求，带上密钥；重排送一对短文本")
    void probesChatAndRerank() {
        var chat = probe.probe(ModelUsage.ANSWER, new ModelRoute(url("ok"), "db-key", "deepseek-chat"));
        var rerank = probe.probe(ModelUsage.RERANK, new ModelRoute(url("ok"), null, "bge-reranker"));

        assertThat(chat.ok()).isTrue();
        assertThat(chat.latencyMs()).isNotNull();
        assertThat(rerank.ok()).isTrue();
        assertThat(requests).anySatisfy(request -> assertThat(request)
                .startsWith("/ok/v1/chat/completions Bearer db-key").contains("\"max_tokens\":1")
                .contains("deepseek-chat"));
        assertThat(requests).anySatisfy(request -> assertThat(request)
                .startsWith("/ok/v1/rerank -").contains("bge-reranker"));
    }

    @Test
    @DisplayName("服务商拒绝时说明原因：模型不存在给出服务商的说明，密钥不对单独说明且不回显服务商原文")
    void explainsRejections() {
        var missing = probe.probe(ModelUsage.ANSWER, new ModelRoute(url("missing"), "k", "nope"));
        var denied = probe.probe(ModelUsage.ANSWER, new ModelRoute(url("denied"), "sk-secret", "m"));

        assertThat(missing.ok()).isFalse();
        assertThat(missing.message()).isEqualTo("服务商返回 404：Model does not exist.");
        assertThat(denied.message()).isEqualTo("服务商返回 401：密钥不对或没有权限").doesNotContain("sk-secret");
    }

    @Test
    @DisplayName("服务商的错误不是 JSON 时只给状态码；说明太长时截断（#145 C8）")
    void handlesUnreadableRejections() {
        assertThat(probe.probe(ModelUsage.ANSWER, new ModelRoute(url("broken"), "k", "m")).message())
                .isEqualTo("服务商返回 500");
        var verbose = probe.probe(ModelUsage.ANSWER, new ModelRoute(url("verbose"), "k", "m")).message();
        assertThat(verbose).startsWith("服务商返回 400：长").endsWith("…");
        assertThat(verbose.length()).isLessThan(300);
    }

    @Test
    @DisplayName("连不上时不抛异常，说明检查地址和网络")
    void reportsUnreachable() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var result = probe.probe(ModelUsage.LIGHT, new ModelRoute("http://127.0.0.1:" + closedPort + "/v1", null, "m"));

        assertThat(result.ok()).isFalse();
        assertThat(result.latencyMs()).isNull();
        assertThat(result.message()).contains("请检查地址和网络");
        assertThat(probe.probe(ModelUsage.ANSWER, new ModelRoute("not a url", null, "m")).message())
                .contains("地址格式不对");
    }

    @Test
    @DisplayName("读服务商的模型列表并排序；不提供列表时提示手动填写")
    void listsModels() {
        assertThat(probe.models(url("ok"), null)).containsExactly("A-model", "b-model");
        assertThatThrownBy(() -> probe.models(url("missing"), null))
                .isInstanceOf(ModelCallException.class).hasMessageContaining("请手动填写模型名称");
        assertThatThrownBy(() -> probe.models(url("empty"), null)).as("列表里没有可用的名字")
                .isInstanceOf(ModelCallException.class).hasMessageContaining("请手动填写模型名称");
        assertThatThrownBy(() -> probe.models("not a url", null))
                .isInstanceOf(ModelCallException.class).hasMessageContaining("地址格式不对");
    }

    @Test
    @DisplayName("环境变量里的默认配置：轻量任务有单独模型，备用回答没配时跟随回答")
    void environmentDefaults() {
        assertThat(probe.environment(ModelUsage.LIGHT).model()).isEqualTo("env-light");
        assertThat(probe.environment(ModelUsage.HEDGE).model()).isEqualTo("env-chat");
        assertThat(probe.environment(ModelUsage.RERANK).model()).isEqualTo("rr");
        assertThat(probe.embedding().model()).isEqualTo("BAAI/bge-m3");
        assertThat(probe.embedding().apiKey()).isNull();
        assertThat(probe.embeddingDimensions()).isEqualTo(1024);
    }

    private void reply(String path, int status, String body) {
        server.createContext(path, exchange -> {
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(path + " " + (auth == null ? "-" : auth) + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private String url(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + prefix + "/v1";
    }
}
