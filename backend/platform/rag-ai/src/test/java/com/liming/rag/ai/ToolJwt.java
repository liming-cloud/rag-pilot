package com.liming.rag.ai;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 测试里用 JWKS 验证令牌的签名并读出声明（和客户系统做的事一样）。 */
final class ToolJwt {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolJwt() {
    }

    record Parsed(Map<String, Object> header, Map<String, Object> claims) {
    }

    /** 签名不对抛 {@link IllegalArgumentException}。 */
    @SuppressWarnings("unchecked") // JWT 的头和声明都是 JSON 对象。
    static Parsed verify(String token, Map<String, Object> jwk) throws Exception {
        var parts = token.split("\\.");
        var decoder = Base64.getUrlDecoder();
        var parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        var spec = parameters.getParameterSpec(ECParameterSpec.class);
        var point = new ECPoint(new BigInteger(1, decoder.decode((String) jwk.get("x"))),
                new BigInteger(1, decoder.decode((String) jwk.get("y"))));
        var publicKey = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initVerify(publicKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(decoder.decode(parts[2]))) {
            throw new IllegalArgumentException("签名不对");
        }
        return new Parsed(JSON.readValue(decoder.decode(parts[0]), Map.class),
                JSON.readValue(decoder.decode(parts[1]), Map.class));
    }
}
