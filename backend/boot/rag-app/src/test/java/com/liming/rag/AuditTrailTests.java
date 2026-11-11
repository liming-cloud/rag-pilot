package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** 管理操作的审计记录（#145 C2）：经接口做的管理操作留下谁、做了什么、对哪个对象；没权限被拒的操作什么都不留。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AuditTrailTests {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("建角色、建账号、改角色、知识库授权与撤销、改系统设置都记下操作人和对象；没权限的操作被拒且不记")
    void recordsAdminActionsOnly() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long adminId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestLogin.ADMIN_USERNAME);

        var role = call(admin, HttpMethod.POST, "/api/roles", Map.of("name", "审计店长", "permissions", List.of("chat:use")));
        long roleId = id(role.getBody());
        var user = call(admin, HttpMethod.POST, "/api/users", Map.of("username", "audit02", "displayName", "审计员工",
                "password", "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http))));
        long userId = id(user.getBody());
        call(admin, HttpMethod.PUT, "/api/users/" + userId + "/roles", Map.of("roleIds", List.of(roleId)));
        long kb = id(call(admin, HttpMethod.POST, "/api/knowledge-bases", Map.of("name", "审计知识库")).getBody());
        call(admin, HttpMethod.PUT, "/api/knowledge-bases/" + kb + "/members/" + userId, null);
        call(admin, HttpMethod.DELETE, "/api/knowledge-bases/" + kb + "/members/" + userId, null);
        call(admin, HttpMethod.PUT, "/api/settings/CHAT_PLACEHOLDER", Map.of("value", "问点什么"));

        var rows = jdbc.queryForList("select actor_id, action, target_type, target_id, detail from audit_log order by id");
        assertThat(rows).extracting(row -> row.get("action")).containsExactly("ROLE_CREATED", "ACCOUNT_CREATED",
                "ACCOUNT_ROLES_CHANGED", "KB_MEMBER_GRANTED", "KB_MEMBER_REVOKED", "SETTING_CHANGED");
        assertThat(rows).allSatisfy(row -> assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(adminId));
        assertThat(rows.get(1)).containsEntry("target_type", "ACCOUNT").containsEntry("target_id", String.valueOf(userId));
        assertThat((String) rows.get(3).get("detail")).contains("审计知识库").contains("audit02");
        assertThat(rows).noneSatisfy(row -> assertThat((String) row.get("detail")).contains("staff-password"));

        String staff = TestLogin.token(http, "audit02", "staff-password");
        var forbidden = call(staff, HttpMethod.PUT, "/api/knowledge-bases/" + kb + "/members/" + userId, null);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isEqualTo(rows.size());
        assertThat(jdbc.queryForObject("select count(*) from knowledge_base_member where knowledge_base_id = ?"
                + " and user_id = ?", Integer.class, kb, userId)).isZero();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private org.springframework.http.ResponseEntity<Map<String, Object>> call(String token, HttpMethod method,
            String path, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        org.springframework.http.ResponseEntity response = http.exchange(path, method, new HttpEntity<>(body, headers),
                Map.class);
        return response;
    }

    private static long id(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
    }
}
