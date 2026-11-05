package com.liming.rag.knowledge.api;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;

/**
 * 嵌入模型不可用：入库或检索时算不出向量。错误码沿用 {@code MODEL_UNAVAILABLE}，接口返回的 {@code code} 不因迁移而变（#158）；
 * message 是给用户看的中文说明，不含密钥、地址或厂商原始响应。
 */
public class EmbeddingUnavailableException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(ErrorKind.UNAVAILABLE, "MODEL_UNAVAILABLE", message, cause);
    }
}
