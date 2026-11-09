package com.liming.rag.ai.tool;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.jspecify.annotations.Nullable;

/**
 * 记下最近一次响应的状态码和 {@code Retry-After}。MCP SDK 对 401/403 以外的非 2xx 响应只抛一个不带状态码的异常，
 * 区分不了 429（不重试、请用户稍后再试）、4xx（不重试）和 502/503/504（重试），所以在 SDK 用的 {@link HttpClient}
 * 外面套一层，响应头到达时记下状态。只记 POST（JSON-RPC 消息）的响应：SDK 握手后另发的 GET（打开 SSE 流，服务器可以回 405）
 * 和关闭会话的 DELETE 与调用结果无关，记下来会把超时误判成协议错误。每次调用一个实例，不跨线程共享。
 */
final class RecordingHttpClient extends HttpClient {

    /** 最近一次响应；还没收到响应时为 null。 */
    record Seen(int status, @Nullable Duration retryAfter) {
    }

    private final HttpClient delegate;
    private final AtomicReference<@Nullable Seen> last;

    private RecordingHttpClient(HttpClient delegate, AtomicReference<@Nullable Seen> last) {
        this.delegate = delegate;
        this.last = last;
    }

    /** 包装 SDK 传输层用的 {@link HttpClient.Builder}：除了 {@code build()} 返回记录状态的客户端，其余都交给原来的 builder。 */
    static HttpClient.Builder recording(HttpClient.Builder builder, AtomicReference<@Nullable Seen> last) {
        var proxy = new HttpClient.Builder[1];
        proxy[0] = (HttpClient.Builder) Proxy.newProxyInstance(HttpClient.Builder.class.getClassLoader(),
                new Class<?>[] {HttpClient.Builder.class}, (self, method, args) -> {
                    if ("build".equals(method.getName())) {
                        return new RecordingHttpClient(builder.build(), last);
                    }
                    var result = method.invoke(builder, args);
                    return result == builder ? proxy[0] : result;
                });
        return proxy[0];
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return delegate.send(request, record(request, handler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler) {
        return delegate.sendAsync(request, record(request, handler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler,
            @Nullable PushPromiseHandler<T> pushHandler) {
        return delegate.sendAsync(request, record(request, handler), pushHandler);
    }

    private <T> BodyHandler<T> record(HttpRequest request, BodyHandler<T> handler) {
        if (!"POST".equals(request.method())) {
            return handler;
        }
        return info -> {
            last.set(new Seen(info.statusCode(), retryAfter(info.headers().firstValue("Retry-After").orElse(null))));
            return handler.apply(info);
        };
    }

    /** Retry-After 只认秒数（规范示例），HTTP 日期形式当作没给。 */
    private static @Nullable Duration retryAfter(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public void shutdownNow() {
        delegate.shutdownNow();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
