package com.liming.rag;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 全部接口唯一的异常出口：返回 RFC 9457 问题详情，{@code detail} 是给用户看的中文说明，{@code code} 是稳定的错误码。
 *
 * <p>业务异常按 {@link ErrorKind} 转状态码；Sa-Token 的未登录、无角色、无权限点转 401、403；Spring MVC 自己的异常
 * （方法不支持、上传过大等）沿用框架的处理并补上 code；其余意外异常记日志、返回 500，不把内部信息带给用户。
 * 流式问答在后台线程里出错时不经过这里，由 {@code AskController} 发 {@code error} 事件。
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ProblemDetail business(BusinessException exception) {
        return problem(status(exception.kind()), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    ProblemDetail notLoggedIn(NotLoginException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
    }

    /** 没有角色或没有权限点都是 403；说明里不写缺的是哪个权限，避免暴露权限设计。 */
    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    ProblemDetail forbidden(RuntimeException exception) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有权限执行此操作");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        LOG.error("接口处理出错", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务出错了，请稍后再试");
    }

    /** Spring MVC 自己的异常沿用框架给的状态码和说明，补上错误码（状态名，如 METHOD_NOT_ALLOWED）。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            var resolved = HttpStatus.resolve(statusCode.value());
            problem.setProperty("code", resolved == null ? "HTTP_" + statusCode.value() : resolved.name());
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request);
    }

    private static HttpStatus status(ErrorKind kind) {
        return switch (kind) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNSUPPORTED -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
