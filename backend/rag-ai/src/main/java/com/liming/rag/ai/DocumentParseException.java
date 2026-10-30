package com.liming.rag.ai;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;

/** 文档无法解析，错误码 {@code DOCUMENT_UNPARSEABLE}。message 是给管理员看的中文原因，会显示在文档列表里。 */
public class DocumentParseException extends BusinessException {

    public DocumentParseException(String message, Throwable cause) {
        super(ErrorKind.INVALID, "DOCUMENT_UNPARSEABLE", message, cause);
    }
}
