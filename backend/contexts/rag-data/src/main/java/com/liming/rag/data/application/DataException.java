package com.liming.rag.data.application;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;

/** 数据问答授权的业务异常。错误码为 {@code DATA_<原因>}，message 是给用户看的中文说明。 */
public class DataException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        NOT_FOUND(ErrorKind.NOT_FOUND),
        INVALID(ErrorKind.INVALID),
        UNAVAILABLE(ErrorKind.UNAVAILABLE);

        private final ErrorKind kind;

        Reason(ErrorKind kind) {
            this.kind = kind;
        }
    }

    private final Reason reason;

    public DataException(Reason reason, String message) {
        super(reason.kind, "DATA_" + reason.name(), message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
