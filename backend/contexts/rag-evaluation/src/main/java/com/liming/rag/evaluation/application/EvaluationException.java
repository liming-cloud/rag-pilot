package com.liming.rag.evaluation.application;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;

/** 知识质量的业务异常。错误码为 {@code EVALUATION_<原因>}，message 是给用户看的中文说明。 */
public class EvaluationException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        NOT_FOUND(ErrorKind.NOT_FOUND),
        INVALID(ErrorKind.INVALID),
        RUNNING(ErrorKind.CONFLICT);

        private final ErrorKind kind;

        Reason(ErrorKind kind) {
            this.kind = kind;
        }
    }

    private final Reason reason;

    public EvaluationException(Reason reason, String message) {
        super(reason.kind, "EVALUATION_" + reason.name(), message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static EvaluationException notFound(String what) {
        return new EvaluationException(Reason.NOT_FOUND, what + "不存在或无权访问");
    }

    public static EvaluationException invalid(String message) {
        return new EvaluationException(Reason.INVALID, message);
    }
}
