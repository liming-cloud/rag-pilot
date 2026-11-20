package com.liming.rag.knowledge.infrastructure.parsing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假的 RapidOCR 服务（#223）：{@code POST /ocr} 默认把第 n 次请求识别成“截图文字 n”；可以改成固定的状态码和响应体。
 */
final class OcrStub implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body;

    OcrStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n = requests.incrementAndGet();
            var json = body != null ? body
                    : "{\"0\":{\"rec_txt\":\"截图文字 " + n + "\",\"dt_boxes\":[[0,0],[90,0],[90,20],[0,20]],\"score\":0.99}}";
            // 和 RapidOCR 用的 uvicorn 一样：不支持升级到 HTTP/2（h2c），这样的请求读不到图片。
            boolean upgrade = exchange.getRequestHeaders().containsKey("Upgrade");
            var bytes = (upgrade ? "Invalid HTTP request" : json).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(upgrade ? 400 : status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requests() {
        return requests.get();
    }

    void respond(int status, String body) {
        this.status = status;
        this.body = body;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
