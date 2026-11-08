package com.liming.rag.ai;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具调用配置（D-058、D-059）。
 *
 * @param signingKey 令牌签名私钥，ES256 的 JWK（JSON，含 {@code x/y/d}，可选 {@code kid}），只从环境变量
 *        {@code RAG_TOOL_SIGNING_KEY} 注入，不进库、不进日志；为空时不能调用工具
 * @param issuer 令牌的 {@code iss}，部署时写进客户系统的配置
 * @param timeout 单次调用超时
 * @param endpoints 连接名到客户 MCP 端点的配置
 */
@ConfigurationProperties("rag.ai.tools")
public record ToolProperties(@Nullable String signingKey, String issuer, Duration timeout,
        Map<String, Endpoint> endpoints) {

    /**
     * @param url MCP 端点的完整地址，如 {@code https://erp.example.com/mcp}
     * @param audience 令牌的 {@code aud}（RFC 8707 的 resource），为空时用 {@code url}
     */
    public record Endpoint(String url, @Nullable String audience) {

        public Endpoint {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("rag.ai.tools.endpoints.*.url 不能为空");
            }
        }

        String resolvedAudience() {
            return audience == null || audience.isBlank() ? url : audience;
        }
    }

    public ToolProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("rag.ai.tools.issuer 不能为空");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("rag.ai.tools.timeout 必须大于 0");
        }
        endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
    }
}
