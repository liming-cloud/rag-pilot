package com.liming.rag.knowledge.application;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;

/**
 * 知识模块的业务异常。错误码为 {@code KNOWLEDGE_<原因>}，message 是给用户看的中文说明。
 */
public class KnowledgeException extends BusinessException {

    public enum Reason {
        NOT_FOUND(ErrorKind.NOT_FOUND),
        CONFLICT(ErrorKind.CONFLICT),
        UNSUPPORTED_TYPE(ErrorKind.UNSUPPORTED),
        INVALID(ErrorKind.INVALID);

        private final ErrorKind kind;

        Reason(ErrorKind kind) {
            this.kind = kind;
        }
    }

    private final Reason reason;

    public KnowledgeException(Reason reason, String message) {
        super(reason.kind, "KNOWLEDGE_" + reason.name(), message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
