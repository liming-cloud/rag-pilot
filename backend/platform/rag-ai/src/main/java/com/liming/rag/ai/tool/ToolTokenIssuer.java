package com.liming.rag.ai.tool;

import com.liming.rag.ai.ToolJwks;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * 签发工具调用令牌（D-058 第 3 节）：ES256 的 JWT，{@code typ=at+jwt}，有效期 60 秒，每次调用一个。
 * 私钥用 JWK 配置（{@link ToolProperties#signingKey()}），公钥由同一个 JWK 的 {@code x/y} 生成 JWKS。
 */
final class ToolTokenIssuer implements ToolJwks {

    static final Duration VALIDITY = Duration.ofSeconds(60);
    /** 服务令牌的 {@code sub}，只用于连接和发现工具，不带数据范围。 */
    static final String SERVICE_SUBJECT = "service:rag-pilot";
    /** 服务令牌的 {@code tool}：不对应具体工具。 */
    static final String ANY_TOOL = "*";

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String issuer;
    private final Clock clock;
    private final @Nullable PrivateKey privateKey;
    private final @Nullable Map<String, Object> publicJwk;

    ToolTokenIssuer(ToolProperties properties, Clock clock) {
        this.issuer = properties.issuer();
        this.clock = clock;
        var jwk = properties.signingKey();
        if (jwk == null || jwk.isBlank()) {
            this.privateKey = null;
            this.publicJwk = null;
        } else {
            var parsed = parse(jwk);
            this.privateKey = privateKey(parsed);
            this.publicJwk = publicJwk(parsed);
        }
    }

    boolean configured() {
        return privateKey != null;
    }

    /** 签发令牌；{@code jti} 由调用方生成，便于先记再发。 */
    String issue(String audience, String tool, String subject, Map<String, Object> dataScope, String jti) {
        if (privateKey == null || publicJwk == null) {
            throw new IllegalStateException("未配置工具调用的签名密钥：请设置环境变量 RAG_TOOL_SIGNING_KEY");
        }
        var now = clock.instant();
        var header = new LinkedHashMap<String, Object>();
        header.put("alg", "ES256");
        header.put("typ", "at+jwt");
        header.put("kid", publicJwk.get("kid"));
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("sub", subject);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(VALIDITY).getEpochSecond());
        claims.put("jti", jti);
        claims.put("tool", tool);
        claims.put("data_scope", dataScope);
        var signingInput = encode(header) + "." + encode(claims);
        return signingInput + "." + BASE64URL.encodeToString(sign(signingInput));
    }

    static String newTokenId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public Map<String, Object> jwks() {
        return Map.of("keys", publicJwk == null ? List.of() : List.of(publicJwk));
    }

    private byte[] sign(String signingInput) {
        try {
            // JWS 的 ES256 签名是 r||s 定长格式（RFC 7515），不是 DER。
            var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("工具调用令牌签名失败", e);
        }
    }

    private static String encode(Map<String, Object> json) {
        return BASE64URL.encodeToString(JSON.writeValueAsBytes(json));
    }

    @SuppressWarnings("unchecked") // JWK 就是 JSON 对象。
    private static Map<String, Object> parse(String jwk) {
        try {
            var parsed = JSON.readValue(jwk, Map.class);
            return (Map<String, Object>) parsed;
        } catch (RuntimeException e) {
            // 不带原因：JWK 里有私钥，解析错误信息可能引用其内容。
            throw new IllegalStateException("RAG_TOOL_SIGNING_KEY 不是合法的 JWK（JSON）");
        }
    }

    private static PrivateKey privateKey(Map<String, Object> jwk) {
        if (!"EC".equals(jwk.get("kty")) || !"P-256".equals(jwk.get("crv")) || !(jwk.get("d") instanceof String d)
                || !(jwk.get("x") instanceof String) || !(jwk.get("y") instanceof String)) {
            throw new IllegalStateException("RAG_TOOL_SIGNING_KEY 必须是 ES256 的 JWK：kty=EC、crv=P-256，含 x、y、d");
        }
        try {
            var parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            var spec = parameters.getParameterSpec(ECParameterSpec.class);
            var secret = new BigInteger(1, Base64.getUrlDecoder().decode(d));
            return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(secret, spec));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("RAG_TOOL_SIGNING_KEY 不是有效的 P-256 私钥");
        }
    }

    private static Map<String, Object> publicJwk(Map<String, Object> jwk) {
        var publicKey = new LinkedHashMap<String, Object>();
        publicKey.put("kty", "EC");
        publicKey.put("crv", "P-256");
        publicKey.put("x", jwk.get("x"));
        publicKey.put("y", jwk.get("y"));
        publicKey.put("use", "sig");
        publicKey.put("alg", "ES256");
        publicKey.put("kid", jwk.get("kid") instanceof String kid && !kid.isBlank() ? kid : thumbprint(publicKey));
        return Map.copyOf(publicKey);
    }

    /** RFC 7638 的 JWK 指纹，没有配置 kid 时作为 kid。 */
    private static String thumbprint(Map<String, Object> publicKey) {
        var canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + publicKey.get("x") + "\",\"y\":\""
                + publicKey.get("y") + "\"}";
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return BASE64URL.encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
