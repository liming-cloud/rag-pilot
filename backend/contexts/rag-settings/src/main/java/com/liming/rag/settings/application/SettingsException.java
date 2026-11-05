package com.liming.rag.settings.application;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;

/** 系统设置的业务异常，错误码为 {@code SETTINGS_<原因>}，message 是给管理员看的中文说明，不含密钥。 */
public class SettingsException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID(ErrorKind.INVALID),
        NOT_FOUND(ErrorKind.NOT_FOUND),
        CONFLICT(ErrorKind.CONFLICT),
        /** 连通测试没通过，没有保存。 */
        UNREACHABLE(ErrorKind.INVALID),
        /** 没设主密钥时要保存密钥。 */
        MASTER_KEY_MISSING(ErrorKind.INVALID),
        /** 存着的密钥解不开（主密钥变了）。 */
        KEY_UNREADABLE(ErrorKind.UNAVAILABLE);

        private final ErrorKind kind;

        Reason(ErrorKind kind) {
            this.kind = kind;
        }
    }

    public SettingsException(Reason reason, String message) {
        super(reason.kind, "SETTINGS_" + reason.name(), message);
    }
}
