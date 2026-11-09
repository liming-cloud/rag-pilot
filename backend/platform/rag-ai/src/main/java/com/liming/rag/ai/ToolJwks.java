package com.liming.rag.ai;

import java.util.Map;

/**
 * 工具调用令牌的验签公钥（JWKS，RFC 7517），提供给客户系统校验；没配置签名密钥时 {@code keys} 为空。
 *
 * <p>用法：web 层的匿名接口直接返回 {@link #jwks()}。不抛业务异常。
 */
public interface ToolJwks {

    Map<String, Object> jwks();
}
