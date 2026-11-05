package com.liming.rag.evaluation.api;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;

/**
 * 评判模型调用失败。错误码沿用 {@code MODEL_UNAVAILABLE}，不因迁移而变（#158）；
 * message 面向运行评测的人，不含密钥、地址或厂商原始响应。
 */
public class JudgeUnavailableException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public JudgeUnavailableException(String message, Throwable cause) {
        super(ErrorKind.UNAVAILABLE, "MODEL_UNAVAILABLE", message, cause);
    }
}
