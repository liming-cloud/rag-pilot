package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.data.api.DataAccess;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 工具与数据范围授权（#181）：授权经接口落库并记审计，判定按用户 ∪ 角色，管理员不绕过，没权限的授权被拒且不写入。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.knowledge.storage.root=${java.io.tmpdir}/rag-data-grant-test-files",
        "rag.data.tools[0].name=get_inventory", "rag.data.tools[0].title=库存查询"})
@AutoConfigureTestRestTemplate
@Testcontainers
class DataGrantTests {

    private static final String TOOL = "get_inventory";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataAccess access;

    @Test
    @DisplayName("授权落库并记审计；判定按用户 ∪ 角色；收回后不再放行；删除角色一并收回；管理员没有授权同样被拒")
    void grantsDriveTheDecision() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long adminId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestLogin.ADMIN_USERNAME);
        long role = id(call(admin, HttpMethod.POST, "/api/roles", Map.of("name", "数据角色", "permissions",
                List.of("chat:use"))).getBody());
        long user = id(call(admin, HttpMethod.POST, "/api/users", Map.of("username", "data01", "displayName", "数据员工",
                "password", "staff-password", "roleIds", List.of(role))).getBody());

        assertThat(decide(adminId).allowed()).as("管理员没有授权，不绕过").isFalse();
        assertThat(decide(user).allowed()).isFalse();

        put(admin, "USER", user, Set.of(TOOL), Set.of("S1"));
        put(admin, "ROLE", role, Set.of(TOOL), Set.of("S2"));

        assertThat(decide(user).units()).containsExactlyInAnyOrder("S1", "S2");
        assertThat(access.decide(new TenantId(1), new UserId(user), TOOL, Set.of("S3"), Set.of()).allowed()).isFalse();
        var view = call(admin, HttpMethod.GET, "/api/data-grants?granteeType=USER&granteeId=" + user, null).getBody();
        assertThat(view).containsEntry("units", List.of("S1")).containsEntry("tools", List.of(TOOL));
        var actions = jdbc.queryForList("select action from audit_log where action like 'DATA_%' order by id",
                String.class);
        assertThat(actions).containsExactly("DATA_GRANTED", "DATA_GRANTED");

        put(admin, "USER", user, Set.of(TOOL), Set.of());
        assertThat(decide(user).units()).as("只收回用户那一条，角色授权仍在").containsExactly("S2");
        assertThat(jdbc.queryForList("select action from audit_log where action like 'DATA_%' order by id", String.class))
                .endsWith("DATA_REVOKED");

        call(admin, HttpMethod.PUT, "/api/users/" + user + "/roles", Map.of("roleIds", List.of(TestLogin.staffRoleId(http))));
        assertThat(decide(user).allowed()).as("账号离开角色后，角色授权不再计入").isFalse();
        assertThat(call(admin, HttpMethod.DELETE, "/api/roles/" + role, null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(decide(user).allowed()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from data_scope_grant where grantee_type = 'ROLE' "
                + "and grantee_id = ?", Integer.class, role)).isZero();
    }

    @Test
    @DisplayName("没有授权权限的账号：授权被拒绝（403），没有写入授权、没有审计；站点主数据取不到时 503")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void withoutPermissionNothingIsWritten() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long user = id(call(admin, HttpMethod.POST, "/api/users", Map.of("username", "data02", "displayName", "普通员工",
                "password", "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http)))).getBody());
        String staff = TestLogin.token(http, "data02", "staff-password");
        int auditBefore = jdbc.queryForObject("select count(*) from audit_log", Integer.class);

        var refused = call(staff, HttpMethod.PUT, "/api/data-grants?granteeType=USER&granteeId=" + user,
                Map.of("tools", List.of(TOOL), "units", List.of("S1"), "regions", List.of()));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("select count(*) from data_scope_grant where grantee_id = ?", Integer.class, user))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isEqualTo(auditBefore);
        assertThat(decide(user).allowed()).isFalse();
        assertThat(http.exchange("/api/data-grants/tools", HttpMethod.GET, new HttpEntity<>(bearer(admin)), List.class)
                .getBody()).hasSize(1);
        assertThat(call(admin, HttpMethod.GET, "/api/data-grants/units", null).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private com.liming.rag.data.api.ScopeDecision decide(long userId) {
        return access.decide(new TenantId(1), new UserId(userId), TOOL, Set.of(), Set.of());
    }

    private void put(String token, String type, long id, Set<String> tools, Set<String> units) {
        var response = call(token, HttpMethod.PUT, "/api/data-grants?granteeType=" + type + "&granteeId=" + id,
                Map.of("tools", List.copyOf(tools), "units", List.copyOf(units), "regions", List.of()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map<String, Object>> call(String token, HttpMethod method, String path, Object body) {
        ResponseEntity response = http.exchange(path, method, new HttpEntity<>(body, bearer(token)), Map.class);
        return response;
    }

    private static HttpHeaders bearer(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static long id(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
    }
}
