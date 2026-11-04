package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 集成测试的登录工具。账号密码是测试专用值，与 test 下的 config/application.yml 一致。
 */
final class TestLogin {

    /** 测试配置里的初始账号，是超级管理员。 */
    static final String ADMIN_USERNAME = "admin";
    static final String ADMIN_PASSWORD = "admin-test-password";

    private TestLogin() {
    }

    static String token(TestRestTemplate http, String username, String password) {
        var response = http.postForEntity("/api/auth/login", Map.of("username", username, "password", password),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("token");
    }

    /** 内置“员工”角色的 id（只有 chat:use），用超级管理员的令牌查。新建账号时用它代替原来的 role=STAFF。 */
    static long staffRoleId(TestRestTemplate http) {
        return builtInRoleId(http, "STAFF");
    }

    static long builtInRoleId(TestRestTemplate http, String builtIn) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token(http, ADMIN_USERNAME, ADMIN_PASSWORD));
        List<?> roles = http.exchange("/api/roles", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), List.class).getBody();
        for (Object role : roles) {
            var fields = (Map<?, ?>) role;
            if (builtIn.equals(fields.get("builtIn"))) {
                return ((Number) fields.get("id")).longValue();
            }
        }
        throw new IllegalStateException("没有内置角色 " + builtIn);
    }

    /** 以管理员身份登录，之后这个客户端的请求默认带上管理员令牌；请求里已显式带了令牌的不覆盖。 */
    static void asAdmin(TestRestTemplate http) {
        var restTemplate = http.getRestTemplate();
        restTemplate.setInterceptors(List.of());
        String token = token(http, ADMIN_USERNAME, ADMIN_PASSWORD);
        restTemplate.setInterceptors(List.of((request, body, execution) -> {
            if (!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(token);
            }
            return execution.execute(request, body);
        }));
    }
}
