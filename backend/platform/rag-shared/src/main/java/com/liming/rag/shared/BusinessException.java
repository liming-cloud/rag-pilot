package com.liming.rag.shared;

import java.util.Objects;

/**
 * 业务失败：带错误分类、稳定的错误码和给用户看的中文说明。各模块的业务异常继承它，入口统一转换，不再各写一套处理器。
 *
 * <p>说明会原样展示给用户，不能包含密钥、地址、SQL 或外部服务的原始响应。
 */
public class BusinessException extends RuntimeException {

    private final ErrorKind kind;
    private final String code;

    /**
     * @param kind    错误分类
     * @param code    客户端可以据此判断的稳定错误码，大写下划线，如 {@code KNOWLEDGE_CONFLICT}
     * @param message 给用户看的中文说明
     */
    public BusinessException(ErrorKind kind, String code, String message) {
        this(kind, code, message, null);
    }

    /**
     * @param cause 原始异常，只用于日志，不展示给用户
     */
    public BusinessException(ErrorKind kind, String code, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        if (code == null || !code.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("错误码必须是大写下划线格式：" + code);
        }
        this.code = code;
    }

    public ErrorKind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
