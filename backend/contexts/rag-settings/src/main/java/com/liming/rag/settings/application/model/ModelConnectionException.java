package com.liming.rag.settings.application.model;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;

/**
 * 连不上模型服务商。错误码沿用 {@code MODEL_UNAVAILABLE}，接口返回的 {@code code} 不因迁移而变（#158）；
 * message 是给管理员看的中文说明，不含密钥、地址或厂商原始响应。
 */
public class ModelConnectionException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ModelConnectionException(String message, Throwable cause) {
        super(ErrorKind.UNAVAILABLE, "MODEL_UNAVAILABLE", message, cause);
    }
}
