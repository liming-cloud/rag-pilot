package com.liming.rag.answering.application;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;

/**
 * 问答模块的业务异常。错误码为 {@code ANSWERING_<原因>}，message 是给用户看的中文说明。
 */
public class AnsweringException extends BusinessException {

    public enum Reason {
        INVALID(ErrorKind.INVALID),
        NOT_FOUND(ErrorKind.NOT_FOUND);

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
