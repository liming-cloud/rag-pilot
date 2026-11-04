package com.liming.rag.ai;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;

/** 调用外部模型失败，错误码 {@code MODEL_UNAVAILABLE}。message 面向管理员展示，不包含密钥、地址或厂商原始响应。 */
public class ModelCallException extends BusinessException {

    public ModelCallException(String message, Throwable cause) {
        super(ErrorKind.UNAVAILABLE, "MODEL_UNAVAILABLE", message, cause);
    }

    public ModelCallException(String message) {
        super(ErrorKind.UNAVAILABLE, "MODEL_UNAVAILABLE", message);
    }
}
