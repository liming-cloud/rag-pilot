package com.liming.rag.identity.application;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;

/**
 * 账号模块的业务异常。错误码为 {@code IDENTITY_<原因>}，message 是给用户看的中文说明。
 */
public class IdentityException extends BusinessException {

    public enum Reason {
        BAD_CREDENTIALS(ErrorKind.UNAUTHENTICATED),
        NOT_FOUND(ErrorKind.NOT_FOUND),
        CONFLICT(ErrorKind.CONFLICT),
        INVALID(ErrorKind.INVALID);

        private final ErrorKind kind;

        Reason(ErrorKind kind) {
            this.kind = kind;
        }
    }

    private final Reason reason;

    public IdentityException(Reason reason, String message) {
        super(reason.kind, "IDENTITY_" + reason.name(), message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
