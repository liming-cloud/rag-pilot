package com.liming.rag.identity.application;

/**
 * 账号模块的业务异常。{@link Reason} 决定 HTTP 状态码，message 是给用户看的中文说明。
 */
public class IdentityException extends RuntimeException {

    public enum Reason {
        BAD_CREDENTIALS,
        CONFLICT,
        INVALID
    }

    private final Reason reason;

    public IdentityException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
