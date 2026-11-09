package com.liming.rag.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingHttpClientTest {

    @Test
    @DisplayName("只记 POST 的响应状态：打开 SSE 流的 GET 回 405 不覆盖，免得超时被误判成协议错误")
    void recordsOnlyPostResponses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.sendResponseHeaders("POST".equals(exchange.getRequestMethod()) ? 200 : 405, -1);
            exchange.close();
        });
        server.start();
        try {
            var seen = new AtomicReference<RecordingHttpClient.@Nullable Seen>();
            var client = RecordingHttpClient.recording(HttpClient.newBuilder(), seen).build();
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");

            client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.discarding());
            client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding());

            assertThat(seen.get()).isNotNull().extracting(RecordingHttpClient.Seen::status).isEqualTo(200);
        } finally {
            server.stop(0);
        }
    }
}
