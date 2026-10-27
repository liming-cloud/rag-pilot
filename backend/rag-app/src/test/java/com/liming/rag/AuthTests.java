package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 登录与角色：未登录 401、角色不够 403；管理员由环境变量初始化（测试值见 test 下的 config/application.yml）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class AuthTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("未登录访问业务接口返回 401")
    void rejectsAnonymous() {
        assertThat(status(HttpMethod.GET, "/api/knowledge-bases", null, null))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, "/api/ask", null, Map.of("question", "货架", "knowledgeBaseIds", 1))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("用户名或密码错误都返回同样的 401 提示")
    void rejectsWrongCredentials() {
        var wrongPassword = login(TestLogin.ADMIN_USERNAME, "wrong-password");
        var unknownUser = login("nobody", "wrong-password");
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("detail")).isEqualTo(unknownUser.getBody().get("detail"));
    }

    @Test
    @DisplayName("管理员建店员账号；店员能读不能管；退出后令牌失效")
    void adminCreatesStaffWithLimitedRights() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        var me = call(HttpMethod.GET, "/api/auth/me", admin, null);
        assertThat(me.getBody()).containsEntry("username", TestLogin.ADMIN_USERNAME).containsEntry("role", "ADMIN");

        var created = call(HttpMethod.POST, "/api/users", admin,
                Map.of("username", "staff01", "displayName", "店员小王", "password", "staff-password", "role", "STAFF"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).doesNotContainKey("password").doesNotContainKey("passwordHash");
        var duplicate = call(HttpMethod.POST, "/api/users", admin,
                Map.of("username", "staff01", "displayName", "重名", "password", "staff-password", "role", "STAFF"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String staff = TestLogin.token(http, "staff01", "staff-password");
        assertThat(status(HttpMethod.GET, "/api/knowledge-bases", staff, null)).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/knowledge-bases", staff, Map.of("name", "店员建的库")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/users", staff,
                Map.of("username", "staff02", "displayName", "x", "password", "staff-password", "role", "ADMIN"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(call(HttpMethod.POST, "/api/auth/logout", staff, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET, "/api/auth/me", staff, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("管理员能列出本租户账号，不含密码；店员不能")
    void listsUsersForAdminOnly() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        call(HttpMethod.POST, "/api/users", admin,
                Map.of("username", "lister01", "displayName", "店员小李", "password", "staff-password", "role", "STAFF"));

        var users = http.exchange("/api/users", HttpMethod.GET, new HttpEntity<>(headers(admin)), String.class);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.getBody()).contains("\"username\":\"admin\"").contains("\"displayName\":\"店员小李\"")
                .doesNotContain("password");

        String staff = TestLogin.token(http, "lister01", "staff-password");
        assertThat(status(HttpMethod.GET, "/api/users", staff, null)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<Map<String, Object>> login(String username, String password) {
        return call(HttpMethod.POST, "/api/auth/login", null, Map.of("username", username, "password", password));
    }

    /** 只看状态码，响应体按字符串读，适合返回数组或空响应的接口。 */
    private HttpStatusCode status(HttpMethod method, String path, String token, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(token)), String.class).getStatusCode();
    }

    private static HttpHeaders headers(String token) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        ResponseEntity response = http.exchange(path, method, new HttpEntity<>(body, headers(token)), Map.class);
        return response;
    }
}
