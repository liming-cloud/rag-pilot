package com.liming.rag.knowledge.web;

import com.liming.rag.knowledge.application.KnowledgeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把知识模块的业务异常转成 RFC 9457 问题详情，detail 是给用户看的中文说明。 */
@RestControllerAdvice(assignableTypes = KnowledgeBaseController.class)
class KnowledgeExceptionHandler {

    @ExceptionHandler(KnowledgeException.class)
    ProblemDetail handle(KnowledgeException exception) {
        var status = switch (exception.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNSUPPORTED_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
        return ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    }
}
