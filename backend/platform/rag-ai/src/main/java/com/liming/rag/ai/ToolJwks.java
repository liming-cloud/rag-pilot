package com.liming.rag.ai;

import java.util.Map;

/** 工具调用令牌的验签公钥（JWKS，RFC 7517），提供给客户系统校验；没配置签名密钥时 {@code keys} 为空。 */
public interface ToolJwks {

    Map<String, Object> jwks();
}
