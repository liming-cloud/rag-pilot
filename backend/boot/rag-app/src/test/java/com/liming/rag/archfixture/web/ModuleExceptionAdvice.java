package com.liming.rag.archfixture.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 违规样例：模块自己写异常处理器，而不是抛业务异常交给 rag-app 的全局处理器。 */
@RestControllerAdvice
public class ModuleExceptionAdvice {
}
