package com.liming.rag.ai;

/** 调用外部模型失败。message 面向管理员展示，不包含密钥、地址或厂商原始响应。 */
public class ModelCallException extends RuntimeException {

    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }

    public ModelCallException(String message) {
        super(message);
    }
}
