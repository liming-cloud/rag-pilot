package com.liming.rag.data.application.query;

import java.io.Serial;

/**
 * 工具调用失败。{@code code} 记进审计（失败分类，工具报错时带上它给的错误码，如 {@code TOOL_ERROR:out_of_scope}）；
 * message 给用户看，不含地址、令牌和客户系统的原始响应。
 */
public class DataToolException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String code;

    public DataToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
