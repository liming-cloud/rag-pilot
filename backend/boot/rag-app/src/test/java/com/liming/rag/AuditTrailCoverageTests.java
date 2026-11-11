package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 审计记录逐类覆盖（#145 C2）：{@link AuditTrailTests} 之外的其余操作——角色修改和删除、账号启停、重置密码、解锁、
 * 批量导入、知识库授权给角色及撤销、替换和删除资料、系统设置恢复默认——经接口做完都留下操作人、操作和对象；
 * 被拒绝的操作不生效、也不留记录。用单独的数据库，断言全表的顺序。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AuditTrailCoverageTests {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("改角色、删角色、账号启停、重置密码、解锁、批量导入、按角色授权与撤销、替换和删除资料、恢复默认设置都记下操作人和对象")
    void recordsRemainingAdminActions() {
        TestLogin.asAdmin(http);
        long adminId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestLogin.ADMIN_USERNAME);

        long roleId = id(send(HttpMethod.POST, "/api/roles", Map.of("name", "覆盖店长", "permissions",
                List.of("chat:use"))));
        send(HttpMethod.PATCH, "/api/roles/" + roleId, Map.of("name", "覆盖店长", "permissions",
                List.of("chat:use", "kb:view")));
        long userId = id(send(HttpMethod.POST, "/api/users", Map.of("username", "cover01", "displayName", "覆盖员工",
                "password", "staff-password", "roleIds", List.of(roleId))));
        send(HttpMethod.PUT, "/api/users/" + userId + "/enabled", Map.of("enabled", false));
        send(HttpMethod.PUT, "/api/users/" + userId + "/enabled", Map.of("enabled", true));
        send(HttpMethod.POST, "/api/users/" + userId + "/reset-password", Map.of("password", "reset-secret-2026"));
        send(HttpMethod.POST, "/api/users/" + userId + "/unlock", null);
        send(HttpMethod.POST, "/api/users/import", Map.of("password", "import-secret-2026", "rows", List.of(
                Map.of("line", 1, "username", "cover02", "displayName", "导入员工", "roleNames", List.of("覆盖店长")))));

        long kb = id(send(HttpMethod.POST, "/api/knowledge-bases", Map.of("name", "覆盖知识库")));
        send(HttpMethod.PUT, "/api/knowledge-bases/" + kb + "/role-grants/" + roleId, null);
        send(HttpMethod.DELETE, "/api/knowledge-bases/" + kb + "/role-grants/" + roleId, null);
        var first = upload("/api/knowledge-bases/" + kb + "/documents", "收银规范.txt", "交班时清点备用金。");
        assertThat(TestDocuments.awaitProcessed(http, kb, first, null)).containsEntry("status", "READY");
        var second = upload("/api/knowledge-bases/" + kb + "/documents/" + id(first) + "/versions", "收银规范.txt",
                "交班时清点备用金，并在交班表上签字。");
        assertThat(TestDocuments.awaitProcessed(http, kb, second, null)).containsEntry("status", "READY");
        send(HttpMethod.DELETE, "/api/knowledge-bases/" + kb + "/documents/" + id(second), null);

        send(HttpMethod.PUT, "/api/settings/CHAT_PLACEHOLDER", Map.of("value", "问点什么"));
        send(HttpMethod.DELETE, "/api/settings/CHAT_PLACEHOLDER", null);
        long spareRole = id(send(HttpMethod.POST, "/api/roles", Map.of("name", "待删角色", "permissions",
                List.of("chat:use"))));
        send(HttpMethod.DELETE, "/api/roles/" + spareRole, null);
        send(HttpMethod.POST, "/api/users", Map.of("username", "cover03", "displayName", "普通员工",
                "password", "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http))));

        var rows = jdbc.queryForList("select actor_id, action, target_type, target_id, detail from audit_log order by id");
        assertThat(rows).extracting(row -> row.get("action") + " " + row.get("target_type")).containsExactly(
                "ROLE_CREATED ROLE", "ROLE_UPDATED ROLE", "ACCOUNT_CREATED ACCOUNT", "ACCOUNT_DISABLED ACCOUNT",
                "ACCOUNT_ENABLED ACCOUNT", "ACCOUNT_PASSWORD_RESET ACCOUNT", "ACCOUNT_UNLOCKED ACCOUNT",
                "ACCOUNTS_IMPORTED ACCOUNT", "KB_ROLE_GRANTED KNOWLEDGE_BASE", "KB_ROLE_REVOKED KNOWLEDGE_BASE",
                "DOCUMENT_REPLACED DOCUMENT", "DOCUMENT_DELETED DOCUMENT", "SETTING_CHANGED SETTING",
                "SETTING_RESTORED SETTING", "ROLE_CREATED ROLE", "ROLE_DELETED ROLE", "ACCOUNT_CREATED ACCOUNT");
        assertThat(rows).allSatisfy(row -> assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(adminId));
        assertThat(rows.get(1)).containsEntry("target_id", String.valueOf(roleId));
        assertThat(rows.subList(3, 7)).allSatisfy(row -> assertThat(row).containsEntry("target_id",
                String.valueOf(userId)));
        assertThat(rows.get(10)).containsEntry("target_id", String.valueOf(id(first)));
        assertThat(rows.get(11)).containsEntry("target_id", String.valueOf(id(second)));
        assertThat(rows.get(13)).containsEntry("target_id", "CHAT_PLACEHOLDER");
        assertThat(rows.get(15)).containsEntry("target_id", String.valueOf(spareRole));
        assertThat(rows).noneSatisfy(row -> assertThat((String) row.get("detail"))
                .containsAnyOf("staff-password", "reset-secret-2026", "import-secret-2026"));

        // 没有权限的操作被拒绝：角色还在，审计记录一条也不多。
        var staff = new HttpHeaders();
        staff.setBearerAuth(TestLogin.token(http, "cover03", "staff-password"));
        var denied = http.exchange("/api/roles/" + roleId, HttpMethod.DELETE, new HttpEntity<>(staff), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("select count(*) from app_role where id = ?", Integer.class, roleId)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isEqualTo(rows.size());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> send(HttpMethod method, String path, Object body) {
        ResponseEntity<Map> response = http.exchange(path, method, new HttpEntity<>(body), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(method + " " + path + "：" + response.getBody())
                .isTrue();
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> upload(String path, String filename, String text) {
        var file = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static long id(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
    }
}
