package com.liming.rag.ai;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * 工具调用令牌的签名密钥工具（ES256 的 JWK，含私钥）。部署时生成一次，放进环境变量 {@code RAG_TOOL_SIGNING_KEY}；
 * 再导出只含公钥的 JWKS 交给客户系统。命令行用法见运维手册：{@code generate [kid]}、{@code jwks <JWK>}。
 */
public final class ToolSigningKeys {

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private ToolSigningKeys() {
    }

    public static void main(String[] args) {
        if (args.length >= 1 && "generate".equals(args[0])) {
            System.out.println(generateJwk(args.length > 1 ? args[1] : "key-1"));
        } else if (args.length == 2 && "jwks".equals(args[0])) {
            System.out.println(publicJwks(args[1]));
        } else {
            System.err.println("用法：generate [kid] | jwks <JWK>");
            System.exit(2);
        }
    }

    /** 从含私钥的 JWK 导出只含公钥的 JWKS（JSON）。 */
    public static String publicJwks(String signingKey) {
        var properties = new ToolProperties(signingKey, "-", java.time.Duration.ofSeconds(1), java.util.Map.of());
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(new ToolTokenIssuer(properties, java.time.Clock.systemUTC()).jwks());
    }

    /** @param keyId JWK 的 {@code kid}，轮换密钥时用来区分新旧 */
    public static String generateJwk(String keyId) {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = generator.generateKeyPair();
            var point = ((ECPublicKey) pair.getPublic()).getW();
            var secret = ((ECPrivateKey) pair.getPrivate()).getS();
            return "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"" + keyId + "\",\"x\":\"" + coordinate(point.getAffineX())
                    + "\",\"y\":\"" + coordinate(point.getAffineY()) + "\",\"d\":\"" + coordinate(secret) + "\"}";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("生成签名密钥失败", e);
        }
    }

    /** 定长 32 字节的大端表示（RFC 7518），不足补零、去掉符号位。 */
    private static String coordinate(java.math.BigInteger value) {
        var bytes = value.toByteArray();
        var fixed = new byte[32];
        var length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, fixed, 32 - length, length);
        return BASE64URL.encodeToString(fixed);
    }
}
