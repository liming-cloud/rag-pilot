package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.identity.api.IdentityPermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 不配 Redis 的默认模式（D-054）：应用不连 Redis 也能启动；缓存只有一级、会话在进程内，改了权限和设置下一个请求就生效。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestRedis.WithoutRedis
class LocalCacheModeTests {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("没有 Redis：能登录；收回角色的权限点后下一个请求 403；改了系统设置后读到新值")
    void worksWithoutRedis() {
        assertThat(context.getBeanNamesForType(RedissonClient.class)).isEmpty();
        String root = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);

        var role = call(root, HttpMethod.POST, "/api/roles",
                Map.of("name", "本地模式角色", "permissions", List.of(IdentityPermissions.USER_VIEW)));
        long roleId = ((Number) role.getBody().get("id")).longValue();
        var user = call(root, HttpMethod.POST, "/api/users", Map.of("username", "local01", "displayName", "本地模式",
                "password", "member-password", "roleIds", List.of(roleId)));
        assertThat(user.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = TestLogin.token(http, "local01", "member-password");
        assertThat(call(token, HttpMethod.GET, "/api/users", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        call(root, HttpMethod.PATCH, "/api/roles/" + roleId, Map.of("permissions", List.of()));
        assertThat(call(token, HttpMethod.GET, "/api/users", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(call(null, HttpMethod.GET, "/api/auth/branding", null).getBody()).containsEntry("name", "明答");
        call(root, HttpMethod.PUT, "/api/settings/BRAND_NAME", Map.of("value", "本地知识库"));
        assertThat(call(null, HttpMethod.GET, "/api/auth/branding", null).getBody()).containsEntry("name", "本地知识库");
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
