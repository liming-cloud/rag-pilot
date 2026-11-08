package com.liming.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 工具调用令牌（D-058 第 3 节）：声明齐全、有效期 ≤ 60 秒、用 JWKS 能验签，换了密钥就验不过。 */
class ToolTokenIssuerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("令牌带规范要求的声明，有效期 60 秒，JWKS 里的公钥能验签")
    void issuesVerifiableToken() throws Exception {
        var issuer = new ToolTokenIssuer(properties(ToolSigningKeys.generateJwk("k1")), CLOCK);

        var token = issuer.issue("https://erp.example.com/mcp", "get_store_inventory", "u-42",
                Map.of("stores", java.util.List.of("S001")), "jti-1");

        @SuppressWarnings("unchecked") // JWKS 的结构由 ToolJwks 约定。
        var jwk = ((java.util.List<Map<String, Object>>) issuer.jwks().get("keys")).get(0);
        var parsed = ToolJwt.verify(token, jwk);
        assertThat(parsed.header()).containsEntry("alg", "ES256").containsEntry("typ", "at+jwt")
                .containsEntry("kid", "k1");
        assertThat(parsed.claims()).containsEntry("iss", "rag-pilot").containsEntry("aud", "https://erp.example.com/mcp")
                .containsEntry("sub", "u-42").containsEntry("jti", "jti-1").containsEntry("tool", "get_store_inventory")
                .containsEntry("data_scope", Map.of("stores", java.util.List.of("S001")));
        var iat = ((Number) parsed.claims().get("iat")).longValue();
        var exp = ((Number) parsed.claims().get("exp")).longValue();
        assertThat(iat).isEqualTo(CLOCK.instant().getEpochSecond());
        assertThat(exp - iat).isEqualTo(60);
        assertThat(jwk).doesNotContainKey("d");
    }

    @Test
    @DisplayName("用别的密钥验签失败；令牌被改动后验签失败")
    void rejectsWrongKeyAndTampering() throws Exception {
        var issuer = new ToolTokenIssuer(properties(ToolSigningKeys.generateJwk("k1")), CLOCK);
        var other = new ToolTokenIssuer(properties(ToolSigningKeys.generateJwk("k2")), CLOCK);
        var token = issuer.issue("aud", "t", "u", Map.of(), "j");

        @SuppressWarnings("unchecked") // JWKS 的结构由 ToolJwks 约定。
        var otherJwk = ((java.util.List<Map<String, Object>>) other.jwks().get("keys")).get(0);
        assertThatThrownBy(() -> ToolJwt.verify(token, otherJwk)).isInstanceOf(IllegalArgumentException.class);
        var parts = token.split("\\.");
        var forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"admin\"}".getBytes()) + "." + parts[2];
        @SuppressWarnings("unchecked") // JWKS 的结构由 ToolJwks 约定。
        var jwk = ((java.util.List<Map<String, Object>>) issuer.jwks().get("keys")).get(0);
        assertThatThrownBy(() -> ToolJwt.verify(forged, jwk)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("没配置密钥时不能签发，JWKS 为空；密钥格式不对时启动失败且报错不含密钥内容")
    void handlesMissingAndInvalidKey() {
        var unconfigured = new ToolTokenIssuer(properties(""), CLOCK);

        assertThat(unconfigured.configured()).isFalse();
        assertThat(unconfigured.jwks().get("keys")).isEqualTo(java.util.List.of());
        assertThatThrownBy(() -> unconfigured.issue("a", "t", "u", Map.of(), "j"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RAG_TOOL_SIGNING_KEY");
        assertThatThrownBy(() -> new ToolTokenIssuer(properties("{\"kty\":\"EC\",\"d\":\"secret-material\"}"), CLOCK))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret-material");
    }

    @Test
    @DisplayName("从私钥导出的 JWKS 只含公钥，能验签")
    void exportsPublicJwksFromSigningKey() throws Exception {
        var key = ToolSigningKeys.generateJwk("k9");
        var issuer = new ToolTokenIssuer(properties(key), CLOCK);

        var exported = ToolSigningKeys.publicJwks(key);

        assertThat(exported).contains("\"kid\":\"k9\"").contains("\"x\"").doesNotContain("\"d\"");
        @SuppressWarnings("unchecked") // JWKS 的结构由 ToolJwks 约定。
        var jwk = ((java.util.List<Map<String, Object>>) issuer.jwks().get("keys")).get(0);
        assertThat(ToolJwt.verify(issuer.issue("a", "t", "u", Map.of(), "j"), jwk).claims()).containsEntry("sub", "u");
    }

    static ToolProperties properties(String signingKey) {
        return new ToolProperties(signingKey, "rag-pilot", Duration.ofSeconds(10), Map.of());
    }
}
