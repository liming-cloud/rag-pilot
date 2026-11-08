package com.liming.rag.ai;

import com.liming.rag.ai.ToolCallException.Reason;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用 Spring AI 底层的 MCP Java SDK 客户端（Streamable HTTP）调用工具（D-058）。
 *
 * <p>SDK 客户端的会话是应用级的，连接和发现工具用服务令牌；而每次 {@code tools/call} 要换成该提问人、该工具的短期令牌，
 * 所以每次调用建一个客户端：先用服务令牌 {@code initialize}，再用用户令牌 {@code tools/call}，用完关闭。令牌经
 * 请求层的 customizer 从传输上下文取，不放进 URL。列出工具（{@code tools/list}）只用服务令牌。
 */
final class McpToolCaller implements ToolCaller {

    private static final Logger log = LoggerFactory.getLogger(McpToolCaller.class);
    private static final String TOKEN_KEY = "rag.tool.token";
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ToolProperties properties;
    private final ToolTokenIssuer tokens;
    private final ObservationRegistry observations;

    McpToolCaller(ToolProperties properties, ToolTokenIssuer tokens, ObservationRegistry observations) {
        this.properties = properties;
        this.tokens = tokens;
        this.observations = observations;
    }

    @Override
    public ToolResult call(ToolCall call) {
        return observed(call, endpoint -> attempt(call, endpoint, client -> {
            var jti = ToolTokenIssuer.newTokenId();
            var result = client.callTool(new CallToolRequest(call.tool(), call.arguments()),
                    tokens.issue(endpoint.resolvedAudience(), call.tool(), call.subject(), call.dataScope(), jti));
            return toResult(call, result, jti);
        }));
    }

    @Override
    public List<ToolDefinition> list(String endpointName) {
        var listing = new ToolCall(endpointName, ToolTokenIssuer.ANY_TOOL, Map.of(), ToolTokenIssuer.SERVICE_SUBJECT,
                Map.of());
        return observed(listing, endpoint -> attempt(listing, endpoint, client -> client.listTools().tools().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.title() == null ? "" : tool.title(),
                        tool.description() == null ? "" : tool.description(),
                        JSON.writeValueAsString(tool.inputSchema() == null ? Map.of() : tool.inputSchema())))
                .toList()));
    }

    private <T> T observed(ToolCall call, Function<ToolProperties.Endpoint, T> action) {
        var endpoint = properties.endpoints().get(call.endpoint());
        if (endpoint == null || !tokens.configured()) {
            throw failed(call, Reason.NOT_CONFIGURED, "数据查询暂未配置，暂时查不到。", null, null, null);
        }
        var observation = Observation.createNotStarted("rag.tool.call", observations)
                .lowCardinalityKeyValue("tool", call.tool()).start();
        try (var scope = observation.openScope()) {
            return withRetry(() -> action.apply(endpoint));
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /** 只读幂等：超时和 502/503/504 最多重试 1 次，间隔不少于 500 毫秒；其余失败不重试。 */
    private static <T> T withRetry(Supplier<T> attempt) {
        try {
            return attempt.get();
        } catch (ToolCallException first) {
            if (first.reason() != Reason.UNAVAILABLE) {
                throw first;
            }
            try {
                Thread.sleep(RETRY_DELAY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return attempt.get();
        }
    }

    /** 建一个客户端，用服务令牌 {@code initialize} 后执行 {@code action}，用完关闭。 */
    private <T> T attempt(ToolCall call, ToolProperties.Endpoint endpoint,
            Function<SessionClient, T> action) {
        var token = new AtomicReference<>(tokens.issue(endpoint.resolvedAudience(), ToolTokenIssuer.ANY_TOOL,
                ToolTokenIssuer.SERVICE_SUBJECT, Map.of(), ToolTokenIssuer.newTokenId()));
        var seen = new AtomicReference<RecordingHttpClient.@Nullable Seen>();
        var uri = URI.create(endpoint.url());
        var base = uri.getScheme() + "://" + uri.getRawAuthority();
        var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        var transport = HttpClientStreamableHttpTransport.builder(base).endpoint(path)
                .clientBuilder(RecordingHttpClient.recording(HttpClient.newBuilder(), seen))
                .connectTimeout(properties.timeout())
                .httpRequestCustomizer((builder, method, target, body, context) ->
                        builder.header("Authorization", "Bearer " + context.get(TOKEN_KEY)))
                .build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(properties.timeout())
                .initializationTimeout(properties.timeout())
                .clientInfo(new Implementation("rag-pilot", "1.0.0"))
                .transportContextProvider(() -> McpTransportContext.create(Map.of(TOKEN_KEY, token.get()))).build()) {
            client.initialize();
            return action.apply(new SessionClient(client, token));
        } catch (ToolCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(call, e, seen.get());
        }
    }

    /** 已握手的客户端；{@code tools/call} 前把令牌换成该提问人、该工具的令牌。 */
    private record SessionClient(McpSyncClient client, AtomicReference<String> token) {

        CallToolResult callTool(CallToolRequest request, String userToken) {
            token.set(userToken);
            return client.callTool(request);
        }

        ListToolsResult listTools() {
            return client.listTools();
        }
    }

    private ToolResult toResult(ToolCall call, CallToolResult result, String jti) {
        var structured = result.structuredContent() instanceof Map<?, ?> map ? stringKeys(map) : null;
        if (Boolean.TRUE.equals(result.isError())) {
            var code = structured != null && structured.get("code") instanceof String c ? c : null;
            var message = structured != null && structured.get("message") instanceof String m ? m : "暂时查不到。";
            throw failed(call, Reason.TOOL_ERROR, message, code, null, null);
        }
        if (structured == null) {
            throw failed(call, Reason.PROTOCOL, "数据查询返回的格式不对，暂时查不到。", null, null, null);
        }
        return new ToolResult(structured, jti);
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        var copy = new java.util.LinkedHashMap<String, Object>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private ToolCallException classify(ToolCall call, RuntimeException e, RecordingHttpClient.@Nullable Seen seen) {
        var status = seen == null ? 0 : seen.status();
        if (status == 401 || status == 403 || hasCause(e, McpHttpClientTransportAuthorizationException.class)) {
            return failed(call, Reason.REJECTED, "数据查询没有通过授权，暂时查不到。", null, null, e);
        }
        if (status == 429) {
            return failed(call, Reason.RATE_LIMITED, "数据查询太频繁，请稍后再试。", null, seen.retryAfter(), e);
        }
        if (status >= 400 && status != 502 && status != 503 && status != 504) {
            return failed(call, Reason.PROTOCOL, "数据查询的协议出错，暂时查不到。", null, null, e);
        }
        var timedOut = hasCause(e, java.util.concurrent.TimeoutException.class)
                || hasCause(e, java.net.http.HttpTimeoutException.class);
        if (!timedOut && findCause(e, McpError.class) != null) {
            return failed(call, Reason.PROTOCOL, "数据查询的协议出错，暂时查不到。", null, null, e);
        }
        // 超时、连不上、502/503/504。
        return failed(call, Reason.UNAVAILABLE, "数据查询暂时不可用，暂时查不到。", null, null, e);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        return findCause(e, type) != null;
    }

    private static <T extends Throwable> @Nullable T findCause(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    /** 失败留一行日志：只记连接名、工具名、原因分类，不记令牌、参数和客户系统的原始响应。 */
    private static ToolCallException failed(ToolCall call, Reason reason, String message, @Nullable String toolCode,
            @Nullable Duration retryAfter, @Nullable Throwable cause) {
        log.warn("event=tool_call_failed endpoint={} tool={} reason={} code={} error={}", call.endpoint(), call.tool(),
                reason, toolCode, cause == null ? "-" : McpError.findRootCause(cause).getClass().getSimpleName());
        return new ToolCallException(reason, message, toolCode, retryAfter, cause);
    }
}
