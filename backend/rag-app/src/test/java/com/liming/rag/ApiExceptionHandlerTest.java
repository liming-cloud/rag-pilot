package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"INVALID, 400", "UNAUTHENTICATED, 401", "FORBIDDEN, 403", "NOT_FOUND, 404", "CONFLICT, 409",
            "UNSUPPORTED, 415", "UNAVAILABLE, 503"})
    @DisplayName("业务异常按错误分类转成状态码，带上稳定的 code 和给用户看的说明")
    void businessErrors(ErrorKind kind, int status) {
        var problem = handler.business(new BusinessException(kind, "SAMPLE_CODE", "给用户看的说明"));

        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getDetail()).isEqualTo("给用户看的说明");
        assertThat(code(problem)).isEqualTo("SAMPLE_CODE");
    }

    @Test
    @DisplayName("未登录 401，没有角色或没有权限点都是 403（M3 改用 @SaCheckPermission 后不能变成 500）")
    void saTokenErrors() {
        var notLogin = handler.notLoggedIn(new NotLoginException("未登录", "login", NotLoginException.NOT_TOKEN));
        assertThat(notLogin.getStatus()).isEqualTo(401);
        assertThat(code(notLogin)).isEqualTo("UNAUTHENTICATED");

        var noRole = handler.forbidden(new NotRoleException("ADMIN"));
        var noPermission = handler.forbidden(new NotPermissionException("knowledge:write"));
        assertThat(noRole.getStatus()).isEqualTo(403);
        assertThat(noPermission.getStatus()).isEqualTo(403);
        assertThat(code(noPermission)).isEqualTo("FORBIDDEN");
        assertThat(noPermission.getDetail()).isEqualTo("没有权限执行此操作").doesNotContain("knowledge:write");
    }

    @Test
    @DisplayName("没有预料到的异常返回 500，不把内部信息带给用户")
    void unexpectedErrors() {
        var problem = handler.unexpected(new IllegalStateException("数据库连接串 jdbc:postgresql://secret"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(code(problem)).isEqualTo("INTERNAL_ERROR");
        assertThat(problem.getDetail()).doesNotContain("jdbc");
    }

    private static Object code(ProblemDetail problem) {
        return problem.getProperties() == null ? null : problem.getProperties().get("code");
    }
}
