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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 查看审计记录（#145 C2，权限点 audit:view）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-audit-query-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class AuditLogQueryTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("内置管理员默认有 audit:view：新的在前，带操作人的名字，可按操作类型和天数筛选；参数不合法 400；没有权限 403")
    @SuppressWarnings("unchecked")
    void listsAuditLogs() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        String adminName = jdbc.queryForObject("select display_name from app_user where username = ?", String.class,
                TestLogin.ADMIN_USERNAME);
        call(admin, HttpMethod.POST, "/api/roles", Map.of("name", "查看审计店长", "permissions", List.of("chat:use")));
        call(admin, HttpMethod.POST, "/api/knowledge-bases", Map.of("name", "查看审计知识库"));
        call(admin, HttpMethod.PUT, "/api/settings/CHAT_PLACEHOLDER", Map.of("value", "问点什么"));

        var all = call(admin, HttpMethod.GET, "/api/audit-logs?days=7", null);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        var items = (List<Map<String, Object>>) all.getBody().get("items");
        assertThat(items).extracting(item -> item.get("action")).containsExactly("SETTING_CHANGED", "ROLE_CREATED");
        assertThat(all.getBody()).containsEntry("total", 2);
        assertThat(items.getFirst()).containsOnlyKeys("id", "actorId", "actorName", "action", "targetType", "targetId",
                "detail", "occurredAt").containsEntry("actorName", adminName);

        var roles = (List<Map<String, Object>>) call(admin, HttpMethod.GET, "/api/audit-logs?action=ROLE_CREATED",
                null).getBody().get("items");
        assertThat(roles).extracting(item -> item.get("action")).containsExactly("ROLE_CREATED");
        assertThat(call(admin, HttpMethod.GET, "/api/audit-logs?days=400", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(admin, HttpMethod.GET, "/api/audit-logs?action=drop%20table", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(jdbc.queryForList("select p.permission from app_role_permission p join app_role r on r.id = p.role_id"
                + " where r.builtin = 'ADMIN'", String.class)).as("内置管理员默认有").contains("audit:view");
        call(admin, HttpMethod.POST, "/api/users", Map.of("username", "audit03", "displayName", "审计店员",
                "password", "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http))));
        String staff = TestLogin.token(http, "audit03", "staff-password");
        var forbidden = call(staff, HttpMethod.GET, "/api/audit-logs", null);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).doesNotContainKey("items");
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

}
