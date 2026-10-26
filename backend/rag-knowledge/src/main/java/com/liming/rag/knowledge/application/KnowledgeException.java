package com.liming.rag.knowledge.application;

/**
 * 知识模块的业务异常。{@link Reason} 决定 HTTP 状态码，message 是给用户看的中文说明。
 */
public class KnowledgeException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        CONFLICT,
        UNSUPPORTED_TYPE,
        INVALID
    }

    private final Reason reason;

    public KnowledgeException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
