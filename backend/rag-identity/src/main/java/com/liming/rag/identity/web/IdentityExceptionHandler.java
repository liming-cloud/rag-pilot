package com.liming.rag.identity.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import com.liming.rag.identity.application.IdentityException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 登录与角色校验的失败对所有接口生效：未登录或令牌失效 401，角色不够 403。
 */
@RestControllerAdvice
class IdentityExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    ProblemDetail notLoggedIn() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "请先登录");
    }

    @ExceptionHandler(NotRoleException.class)
    ProblemDetail forbidden() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "没有权限执行此操作");
    }

    @ExceptionHandler(IdentityException.class)
    ProblemDetail handle(IdentityException exception) {
        var status = switch (exception.reason()) {
            case BAD_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
        return ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    }
}
