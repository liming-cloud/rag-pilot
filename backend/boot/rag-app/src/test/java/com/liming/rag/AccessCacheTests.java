package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.identity.api.IdentityPermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 账号权限缓存（D-053，修订 D-041）：权限点和角色缓存在 Redis，改角色的权限点、分配角色、停用、重置密码后下一个请求
 * 就按新权限判定。先请求一次把缓存填上，再改，再断言下一个请求的结果。会话也在 Redis 里。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class AccessCacheTests {

    private static final String PASSWORD = "member-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private RedissonClient redis;

    private final Map<Long, String> usernames = new java.util.HashMap<>();
    private static int sequence;

    @Test
    @DisplayName("改角色的权限点：收回后下一个请求 403，再给回后 200；缓存确实在 Redis 里，会话也在")
    void rolePermissionChangeAppliesToNextRequest() {
        String root = rootToken();
        long role = createRole(root, List.of(IdentityPermissions.USER_VIEW));
        long user = createUser(root, role);
        String token = login(user);

        assertThat(listUsers(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(keys("rag:cache:access:*")).contains("rag:cache:access:" + user);
        // Sa-Token 的键以 token-name 开头：Authorization:login:token:<令牌>。
        assertThat(keys("Authorization:login:token:*")).as("会话存在 Redis 里（修订 D-005）").isNotEmpty();

        assertThat(call(root, HttpMethod.PATCH, "/api/roles/" + role, Map.of("permissions", List.of()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listUsers(token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        call(root, HttpMethod.PATCH, "/api/roles/" + role, Map.of("permissions", List.of(IdentityPermissions.USER_VIEW)));
        assertThat(listUsers(token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("分配角色：换成没有权限点的角色后下一个请求 403，换回后 200")
    void roleAssignmentAppliesToNextRequest() {
        String root = rootToken();
        long viewer = createRole(root, List.of(IdentityPermissions.USER_VIEW));
        long nothing = createRole(root, List.of());
        long user = createUser(root, viewer);
        String token = login(user);
        assertThat(listUsers(token).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(call(root, HttpMethod.PUT, "/api/users/" + user + "/roles", Map.of("roleIds", List.of(nothing)))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listUsers(token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(permissions(token)).isEmpty();

        call(root, HttpMethod.PUT, "/api/users/" + user + "/roles", Map.of("roleIds", List.of(viewer)));
        assertThat(listUsers(token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("停用：旧令牌失效、不能登录；启用后重新登录按原权限")
    void disableAppliesToNextRequest() {
        String root = rootToken();
        long user = createUser(root, createRole(root, List.of(IdentityPermissions.USER_VIEW)));
        String token = login(user);
        assertThat(listUsers(token).getStatusCode()).isEqualTo(HttpStatus.OK);

        call(root, HttpMethod.PUT, "/api/users/" + user + "/enabled", Map.of("enabled", false));
        assertThat(listUsers(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rawLogin(user).getStatusCode()).isNotEqualTo(HttpStatus.OK);

        call(root, HttpMethod.PUT, "/api/users/" + user + "/enabled", Map.of("enabled", true));
        assertThat(listUsers(login(user)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("重置密码：重新登录后改密码之前没有任何权限点，改完下一个请求恢复")
    void passwordResetAppliesToNextRequest() {
        String root = rootToken();
        long user = createUser(root, createRole(root, List.of(IdentityPermissions.USER_VIEW)));
        assertThat(listUsers(login(user)).getStatusCode()).isEqualTo(HttpStatus.OK);

        call(root, HttpMethod.POST, "/api/users/" + user + "/reset-password", Map.of("password", "temp-password"));
        var temporary = (String) rawLogin(user, "temp-password").getBody().get("token");
        assertThat(listUsers(temporary).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(permissions(temporary)).isEmpty();

        assertThat(call(temporary, HttpMethod.POST, "/api/auth/password",
                Map.of("oldPassword", "temp-password", "newPassword", "changed-password")).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(listUsers(temporary).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> listUsers(String token) {
        return call(token, HttpMethod.GET, "/api/users", null);
    }

    @SuppressWarnings("unchecked")
    private List<String> permissions(String token) {
        return (List<String>) call(token, HttpMethod.GET, "/api/auth/me", null).getBody().get("permissions");
    }

    private List<String> keys(String pattern) {
        var found = new ArrayList<String>();
        redis.getKeys().getKeys(KeysScanOptions.defaults().pattern(pattern)).forEach(found::add);
        return found;
    }

    private long createRole(String root, List<String> permissions) {
        var created = call(root, HttpMethod.POST, "/api/roles",
                Map.of("name", "缓存测试角色" + (++sequence), "permissions", permissions));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private long createUser(String root, long roleId) {
        var username = "cache" + (++sequence);
        var created = call(root, HttpMethod.POST, "/api/users", Map.of("username", username,
                "displayName", "缓存测试", "password", PASSWORD, "roleIds", List.of(roleId)));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = ((Number) created.getBody().get("id")).longValue();
        usernames.put(id, username);
        return id;
    }

    private String login(long userId) {
        var response = rawLogin(userId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("token");
    }

    private ResponseEntity<Map<String, Object>> rawLogin(long userId) {
        return rawLogin(userId, PASSWORD);
    }

    private ResponseEntity<Map<String, Object>> rawLogin(long userId, String password) {
        return call(null, HttpMethod.POST, "/api/auth/login",
                Map.of("username", usernames.get(userId), "password", password));
    }

    private String rootToken() {
        return TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
    }

    private ResponseEntity<Map<String, Object>> call(String token, HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() { });
    }
}
