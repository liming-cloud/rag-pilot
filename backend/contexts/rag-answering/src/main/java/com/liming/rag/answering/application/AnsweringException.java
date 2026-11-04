package com.liming.rag.answering.application;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;

/**
 * 问答模块的业务异常。错误码为 {@code ANSWERING_<原因>}，message 是给用户看的中文说明。
 */
public class AnsweringException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID(ErrorKind.INVALID),
        NOT_FOUND(ErrorKind.NOT_FOUND),
        /** 会话刚被别的操作改过（同时在两个窗口提问等），这次修改没有保存。 */
        CONFLICT(ErrorKind.CONFLICT),
        /** 提问超过频率上限（#145）。 */
        RATE_LIMITED(ErrorKind.TOO_MANY_REQUESTS);

        private final ErrorKind kind;

        Reason(ErrorKind kind) {
            this.kind = kind;
        }
    }

    private final Reason reason;

    public AnsweringException(Reason reason, String message) {
        super(reason.kind, "ANSWERING_" + reason.name(), message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
