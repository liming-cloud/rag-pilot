package com.liming.rag.ai.common;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;
import org.springframework.modulith.NamedInterface;

/** 调用外部模型失败，错误码 {@code MODEL_UNAVAILABLE}。message 面向管理员展示，不包含密钥、地址或厂商原始响应。 */
@NamedInterface("common")
public class ModelCallException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ModelCallException(String message, Throwable cause) {
        super(ErrorKind.UNAVAILABLE, "MODEL_UNAVAILABLE", message, cause);
    }

    public ModelCallException(String message) {
        super(ErrorKind.UNAVAILABLE, "MODEL_UNAVAILABLE", message);
    }
}
