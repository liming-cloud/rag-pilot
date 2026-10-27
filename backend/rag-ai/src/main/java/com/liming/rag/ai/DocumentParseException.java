package com.liming.rag.ai;

/** 文档无法解析。message 是给管理员看的中文原因，会显示在文档列表里。 */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
