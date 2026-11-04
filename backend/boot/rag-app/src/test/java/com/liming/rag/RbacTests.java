package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.api.AnsweringPermissions;
import com.liming.rag.identity.api.IdentityPermissions;
import com.liming.rag.knowledge.api.KnowledgePermissions;
import com.liming.rag.settings.api.SettingsPermissions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 按钮级 RBAC（D-038）：每个权限点“有权限能调、没权限 403”；超级管理员固定；防止越权分配；重置密码、改密码、
 * 首次登录强制改密、登录失败锁定与解锁。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-rbac-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class RbacTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private int sequence;

    /** 一个接口和它要求的权限点。路径里的 {kb}、{user}、{role} 由测试替换成真实的 id。 */
    record Endpoint(String permission, HttpMethod method, String path, Object body) {
    }

    @Test
    @DisplayName("权限点清单由各上下文声明、identity 汇总（#136）：角色页的菜单顺序和权限点与汇总前一致")
    @SuppressWarnings("unchecked")
    void catalogKeepsMenusAndCodes() {
        var catalog = http.exchange("/api/permissions", HttpMethod.GET, new HttpEntity<>(bearer(rootToken())),
                List.class).getBody();

        assertThat(catalog).extracting(group -> ((Map<String, Object>) group).get("menu")).containsExactly(
                "对话", "知识库管理", "用户管理", "角色与权限", "模型管理", "回答记录", "运营概览", "系统设置");
        var codes = new ArrayList<String>();
        for (var group : (List<Map<String, Object>>) catalog) {
            codes.add((String) ((Map<String, Object>) group.get("view")).get("code"));
            ((List<Map<String, Object>>) group.get("buttons")).forEach(button -> codes.add((String) button.get("code")));
        }
        assertThat(codes).containsExactlyElementsOf(AllPermissions.ALL).hasSize(32);
    }

    @Test
    @DisplayName("每个接口：只有这一个权限点的账号能调；有其他全部权限、唯独缺它的账号返回 403")
    void everyEndpointChecksItsPermission() {
        String root = rootToken();
        long kb = id(call(root, HttpMethod.POST, "/api/knowledge-bases", Map.of("name", "权限测试库")));
        long staffRole = TestLogin.staffRoleId(http);
        long target = createUser(root, "target01", staffRole);
        long dummyRole = createRole(root, "待删角色", List.of(IdentityPermissions.CHAT_USE));

        var endpoints = List.of(
                new Endpoint(IdentityPermissions.CHAT_USE, HttpMethod.GET, "/api/knowledge-bases", null),
                new Endpoint(IdentityPermissions.CHAT_USE, HttpMethod.GET, "/api/conversations", null),
                new Endpoint(KnowledgePermissions.KB_VIEW, HttpMethod.GET, "/api/knowledge-bases/page", null),
                new Endpoint(KnowledgePermissions.KB_VIEW, HttpMethod.GET, "/api/knowledge-bases/{kb}", null),
                new Endpoint(KnowledgePermissions.KB_VIEW, HttpMethod.GET, "/api/knowledge-bases/{kb}/documents", null),
                new Endpoint(KnowledgePermissions.KB_VIEW, HttpMethod.GET, "/api/knowledge-bases/{kb}/search?q=货架", null),
                new Endpoint(KnowledgePermissions.KB_VIEW, HttpMethod.GET, "/api/knowledge-bases/expiry-summary", null),
                new Endpoint(KnowledgePermissions.KB_CREATE, HttpMethod.POST, "/api/knowledge-bases", Map.of("name", "新库")),
                new Endpoint(KnowledgePermissions.DOC_UPLOAD, HttpMethod.POST, "/api/knowledge-bases/{kb}/documents",
                        file("上传.txt")),
                new Endpoint(KnowledgePermissions.DOC_REPLACE, HttpMethod.POST,
                        "/api/knowledge-bases/{kb}/documents/999999/versions", file("新版.txt")),
                new Endpoint(KnowledgePermissions.DOC_UPDATE, HttpMethod.PUT,
                        "/api/knowledge-bases/{kb}/documents/999999/validity", Map.of("expiresOn", "")),
                new Endpoint(KnowledgePermissions.DOC_DELETE, HttpMethod.DELETE, "/api/knowledge-bases/{kb}/documents/999999",
                        null),
                new Endpoint(KnowledgePermissions.KB_GRANT, HttpMethod.GET, "/api/knowledge-bases/{kb}/members", null),
                new Endpoint(KnowledgePermissions.KB_GRANT, HttpMethod.GET, "/api/knowledge-bases/{kb}/role-grants", null),
                new Endpoint(KnowledgePermissions.KB_GRANT, HttpMethod.PUT, "/api/knowledge-bases/{kb}/members/{user}", null),
                new Endpoint(KnowledgePermissions.KB_GRANT, HttpMethod.PUT, "/api/knowledge-bases/{kb}/role-grants/{role}",
                        null),
                new Endpoint(IdentityPermissions.USER_VIEW, HttpMethod.GET, "/api/users", null),
                new Endpoint(IdentityPermissions.USER_CREATE, HttpMethod.POST, "/api/users", Map.of("username", "made01",
                        "displayName", "新人", "password", "made-password", "roleIds", List.of(staffRole))),
                new Endpoint(IdentityPermissions.USER_UPDATE, HttpMethod.POST, "/api/users/{user}/unlock", null),
                new Endpoint(IdentityPermissions.USER_DISABLE, HttpMethod.PUT, "/api/users/{user}/enabled",
                        Map.of("enabled", true)),
                new Endpoint(IdentityPermissions.USER_RESET_PASSWORD, HttpMethod.POST, "/api/users/{user}/reset-password",
                        Map.of("password", "temp-password")),
                new Endpoint(IdentityPermissions.USER_ASSIGN_ROLE, HttpMethod.PUT, "/api/users/{user}/roles",
                        Map.of("roleIds", List.of(staffRole))),
                new Endpoint(IdentityPermissions.USER_IMPORT, HttpMethod.POST, "/api/users/import/check",
                        Map.of("rows", List.of(importRow(2, "check01", staffRole)))),
                new Endpoint(IdentityPermissions.USER_IMPORT, HttpMethod.POST, "/api/users/import",
                        Map.of("rows", List.of(), "password", "import-password")),
                new Endpoint(IdentityPermissions.ROLE_VIEW, HttpMethod.GET, "/api/permissions", null),
                new Endpoint(IdentityPermissions.ROLE_CREATE, HttpMethod.POST, "/api/roles",
                        Map.of("name", "临时角色", "permissions", List.of())),
                new Endpoint(IdentityPermissions.ROLE_UPDATE, HttpMethod.PATCH, "/api/roles/{role}",
                        Map.of("permissions", List.of())),
                new Endpoint(AnsweringPermissions.ANSWER_VIEW, HttpMethod.GET, "/api/answers", null),
                new Endpoint(AnsweringPermissions.ANSWER_REVIEW, HttpMethod.PATCH, "/api/answers/999999/review",
                        Map.of("status", "RESOLVED")),
                new Endpoint(AnsweringPermissions.ANSWER_RECHECK, HttpMethod.POST, "/api/answers/999999/recheck", null),
                new Endpoint(AnsweringPermissions.STATS_VIEW, HttpMethod.GET, "/api/answers/stats", null),
                new Endpoint(AnsweringPermissions.STATS_EXPORT, HttpMethod.GET, "/api/answers/stats/export?days=7", null),
                new Endpoint(AnsweringPermissions.ANSWER_VIEW, HttpMethod.GET, "/api/answers/gaps", null),
                new Endpoint(AnsweringPermissions.ANSWER_REVIEW, HttpMethod.POST, "/api/answers/gaps/resolve",
                        Map.of("recordIds", List.of(999999))),
                // 模型管理：用不存在的服务商，权限通过后也不会真的去连外部模型服务。
                new Endpoint(SettingsPermissions.MODEL_VIEW, HttpMethod.GET, "/api/models", null),
                new Endpoint(SettingsPermissions.MODEL_VIEW, HttpMethod.GET, "/api/models/changes", null),
                new Endpoint(SettingsPermissions.MODEL_TEST, HttpMethod.POST, "/api/models/test",
                        Map.of("usage", "RERANK", "providerId", 999999, "model", "m")),
                new Endpoint(SettingsPermissions.MODEL_UPDATE, HttpMethod.GET, "/api/models/available?providerId=999999", null),
                new Endpoint(SettingsPermissions.MODEL_UPDATE, HttpMethod.PUT, "/api/models/RERANK",
                        Map.of("providerId", 999999, "model", "m")),
                new Endpoint(SettingsPermissions.MODEL_UPDATE, HttpMethod.POST, "/api/models/changes/999999/restore", null),
                new Endpoint(SettingsPermissions.MODEL_UPDATE, HttpMethod.POST, "/api/model-providers",
                        Map.of("name", "权限测试服务商", "baseUrl", "http://127.0.0.1:9/v1")),
                new Endpoint(SettingsPermissions.MODEL_UPDATE, HttpMethod.DELETE, "/api/model-providers/999999", null),
                new Endpoint(IdentityPermissions.CHAT_USE, HttpMethod.GET, "/api/settings/chat", null),
                new Endpoint(SettingsPermissions.SETTING_VIEW, HttpMethod.GET, "/api/settings", null),
                new Endpoint(SettingsPermissions.SETTING_UPDATE, HttpMethod.PUT, "/api/settings/CHAT_PLACEHOLDER",
                        Map.of("value", "权限测试")),
                new Endpoint(SettingsPermissions.SETTING_UPDATE, HttpMethod.DELETE, "/api/settings/CHAT_PLACEHOLDER", null),
                new Endpoint(IdentityPermissions.ROLE_DELETE, HttpMethod.DELETE, "/api/roles/{role}", null));

        var allowed = new HashMap<String, String>();
        var denied = new HashMap<String, String>();
        var problems = new ArrayList<String>();
        for (var endpoint : endpoints) {
            var permission = endpoint.permission();
            var with = allowed.computeIfAbsent(permission, p -> userWith(root, List.of(p)));
            var without = denied.computeIfAbsent(permission, p -> userWith(root,
                    AllPermissions.ALL.stream().filter(code -> !code.equals(p)).toList()));
            var path = endpoint.path().replace("{kb}", String.valueOf(kb)).replace("{user}", String.valueOf(target))
                    .replace("{role}", String.valueOf(dummyRole));
            var granted = send(with, endpoint.method(), path, endpoint.body());
            var refused = send(without, endpoint.method(), path, endpoint.body());
            if (isPermissionDenied(granted)) {
                problems.add(permission + " " + endpoint.method() + " " + path + "：有权限却被拒绝 " + granted.getBody());
            }
            if (!isPermissionDenied(refused)) {
                problems.add(permission + " " + endpoint.method() + " " + path + "：没有权限却返回 "
                        + refused.getStatusCode());
            }
        }
        assertThat(problems).isEmpty();

        // 角色列表在角色页、用户页、知识库授权都要用：三个权限点任一即可读，一个都没有时 403（D-041）。
        for (var permission : List.of(IdentityPermissions.ROLE_VIEW, IdentityPermissions.USER_VIEW, KnowledgePermissions.KB_GRANT)) {
            assertThat(isPermissionDenied(send(userWith(root, List.of(permission)), HttpMethod.GET, "/api/roles",
                    null))).as("只有 %s 时能读角色列表", permission).isFalse();
        }
        var none = userWith(root, AllPermissions.ALL.stream().filter(code -> !List.of(IdentityPermissions.ROLE_VIEW,
                IdentityPermissions.USER_VIEW, KnowledgePermissions.KB_GRANT).contains(code)).toList());
        assertThat(isPermissionDenied(send(none, HttpMethod.GET, "/api/roles", null))).isTrue();
    }

    @Test
    @DisplayName("超级管理员不能被别人停用、重置密码、改角色；超级管理员角色不能分配、修改；越权分配被拒绝")
    void superAdminIsFixedAndEscalationIsRefused() {
        String root = rootToken();
        long rootId = id(call(root, HttpMethod.GET, "/api/auth/me", null));
        long superRole = TestLogin.builtInRoleId(http, "SUPER_ADMIN");
        long adminRole = TestLogin.builtInRoleId(http, "ADMIN");
        long staffRole = TestLogin.staffRoleId(http);
        createUser(root, "admin01", adminRole);
        long peer = createUser(root, "admin02", adminRole);
        long staff = createUser(root, "staff01", staffRole);
        long modelRole = createRole(root, "模型员", List.of(SettingsPermissions.MODEL_VIEW));
        String admin = login("admin01", "member-password");

        assertEscalation(call(admin, HttpMethod.PUT, "/api/users/" + rootId + "/enabled", Map.of("enabled", false)));
        assertEscalation(call(admin, HttpMethod.POST, "/api/users/" + rootId + "/reset-password",
                Map.of("password", "hijack-password")));
        assertEscalation(call(admin, HttpMethod.PUT, "/api/users/" + rootId + "/roles",
                Map.of("roleIds", List.of(staffRole))));
        assertEscalation(call(admin, HttpMethod.PUT, "/api/users/" + peer + "/enabled", Map.of("enabled", false)));
        assertEscalation(call(admin, HttpMethod.PUT, "/api/users/" + staff + "/roles",
                Map.of("roleIds", List.of(superRole))));
        assertEscalation(call(admin, HttpMethod.PUT, "/api/users/" + staff + "/roles",
                Map.of("roleIds", List.of(modelRole))));
        assertEscalation(call(admin, HttpMethod.POST, "/api/users", Map.of("username", "sneaky01",
                "displayName", "x", "password", "sneaky-password", "roleIds", List.of(modelRole))));
        assertEscalation(call(root, HttpMethod.PUT, "/api/users/" + staff + "/roles",
                Map.of("roleIds", List.of(superRole))));

        assertThat(call(root, HttpMethod.PATCH, "/api/roles/" + superRole, Map.of("permissions", List.of()))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(root, HttpMethod.DELETE, "/api/roles/" + adminRole, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // 没有被改动：超级管理员仍能登录、仍是超级管理员；店员仍只是员工。
        assertThat(call(rootToken(), HttpMethod.GET, "/api/auth/me", null).getBody()).containsEntry("superAdmin", true);
        assertThat(login("sneaky01", "sneaky-password")).isNull();
        assertThat((List<Object>) call(login("staff01", "member-password"), HttpMethod.GET, "/api/auth/me", null)
                .getBody().get("permissions")).containsExactly(IdentityPermissions.CHAT_USE);
        // 超级管理员能管理其他管理员。
        assertThat(call(root, HttpMethod.PUT, "/api/users/" + peer + "/enabled", Map.of("enabled", false))
                .getBody()).containsEntry("enabled", false);
    }

    @Test
    @DisplayName("重置密码后旧会话失效、必须先改密码，改之前没有任何权限；改密码要旧密码且不能与用户名相同")
    void resetAndChangePassword() {
        String root = rootToken();
        long staff = createUser(root, "reset01", TestLogin.staffRoleId(http));
        String before = login("reset01", "member-password");

        assertThat(call(root, HttpMethod.POST, "/api/users/" + staff + "/reset-password",
                Map.of("password", "temp-password")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(before, HttpMethod.GET, "/api/auth/me", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login("reset01", "member-password")).isNull();

        String forced = login("reset01", "temp-password");
        var me = call(forced, HttpMethod.GET, "/api/auth/me", null).getBody();
        assertThat(me).containsEntry("mustChangePassword", true).containsEntry("permissions", List.of());
        assertThat(call(forced, HttpMethod.GET, "/api/knowledge-bases", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(changePassword(forced, "wrong-password", "brand-new-password").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(changePassword(forced, "temp-password", "reset01").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(changePassword(forced, "temp-password", "brand-new-password").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(call(forced, HttpMethod.GET, "/api/auth/me", null).getBody())
                .containsEntry("mustChangePassword", false).containsEntry("permissions", List.of("chat:use"));
        assertThat(call(forced, HttpMethod.GET, "/api/knowledge-bases", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(login("reset01", "brand-new-password")).isNotNull();
    }

    @Test
    @DisplayName("连续输错 5 次锁定，锁定期间密码对了也不能登录；管理员解锁后恢复")
    void locksAndUnlocks() {
        String root = rootToken();
        long staff = createUser(root, "lock01", TestLogin.staffRoleId(http));
        for (int i = 0; i < 4; i++) {
            assertThat(rawLogin("lock01", "wrong-password").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        var fifth = rawLogin("lock01", "wrong-password");
        assertThat(fifth.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fifth.getBody()).containsEntry("code", "IDENTITY_LOCKED");
        var locked = rawLogin("lock01", "member-password");
        assertThat(locked.getBody()).containsEntry("code", "IDENTITY_LOCKED");
        assertThat(locked.getBody()).doesNotContainKey("token");

        var listed = call(root, HttpMethod.GET, "/api/users?status=locked", null).getBody();
        assertThat(listed).containsEntry("total", 1);

        assertThat(call(root, HttpMethod.POST, "/api/users/" + staff + "/unlock", null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login("lock01", "member-password")).isNotNull();
    }

    @Test
    @DisplayName("同时发来的多次错误密码也会累计并锁定，不能靠并发绕过（#145 C4）")
    void locksUnderConcurrentWrongPasswords() throws Exception {
        String root = rootToken();
        createUser(root, "lock02", TestLogin.staffRoleId(http));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    return rawLogin("lock02", "wrong-password").getStatusCode();
                }));
            }
            start.countDown();
            for (var attempt : attempts) {
                attempt.get();
            }
        }

        var afterwards = rawLogin("lock02", "member-password");
        assertThat(afterwards.getBody()).as("并发输错 12 次后，密码对了也应因锁定登不上").containsEntry("code", "IDENTITY_LOCKED");
        assertThat(afterwards.getBody()).doesNotContainKey("token");
    }

    @Test
    @DisplayName("账号列表按关键词、角色、状态筛选并分页；每页最多 100 条")
    void pagesUsers() {
        String root = rootToken();
        long staffRole = TestLogin.staffRoleId(http);
        long manager = createRole(root, "店长", List.of(IdentityPermissions.CHAT_USE, AnsweringPermissions.ANSWER_VIEW));
        for (int i = 1; i <= 3; i++) {
            createUser(root, "pager0" + i, staffRole);
        }
        long boss = createUser(root, "pager09", manager);
        call(root, HttpMethod.PUT, "/api/users/" + boss + "/enabled", Map.of("enabled", false));

        var first = call(root, HttpMethod.GET, "/api/users?keyword=PAGER&page=1&size=2", null).getBody();
        assertThat(first).containsEntry("total", 4).containsEntry("page", 1).containsEntry("size", 2);
        assertThat((List<?>) first.get("items")).hasSize(2);
        assertThat(call(root, HttpMethod.GET, "/api/users?keyword=pager&roleId=" + manager, null).getBody())
                .containsEntry("total", 1);
        assertThat(call(root, HttpMethod.GET, "/api/users?keyword=pager&status=disabled", null).getBody())
                .containsEntry("total", 1);
        assertThat(call(root, HttpMethod.GET, "/api/users?size=1000", null).getBody()).containsEntry("size", 100);
        assertThat(call(root, HttpMethod.GET, "/api/users?status=gone", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("批量导入：全部通过时导入且都必须先改密码；任一行有问题、越权或没有权限时一条都不写入")
    @SuppressWarnings("unchecked")
    void importsAccounts() {
        String root = rootToken();
        long staffRole = TestLogin.staffRoleId(http);
        String staffName = roleName(root, staffRole);
        createRole(root, "导入测试模型员", List.of(SettingsPermissions.MODEL_VIEW));
        createUser(root, "importer01", TestLogin.builtInRoleId(http, "ADMIN"));
        String admin = login("importer01", "member-password");

        var good = List.of(row(2, "import01", "导入员工一", staffName), row(3, "import02", "导入员工二", staffName));
        assertThat(call(admin, HttpMethod.POST, "/api/users/import/check", Map.of("rows", good)).getBody())
                .containsEntry("problems", List.of());
        assertThat(call(admin, HttpMethod.POST, "/api/users/import", Map.of("rows", good,
                "password", "Welcome-2026")).getBody()).containsEntry("imported", 2);
        String first = login("import01", "Welcome-2026");
        assertThat(call(first, HttpMethod.GET, "/api/auth/me", null).getBody())
                .containsEntry("mustChangePassword", true).containsEntry("permissions", List.of());

        var bad = List.of(row(2, "import03", "好的一行", staffName), row(3, "import01", "已存在", staffName),
                row(4, "import04", "越权", "导入测试模型员"));
        var problems = (List<Map<String, Object>>) call(admin, HttpMethod.POST, "/api/users/import/check",
                Map.of("rows", bad)).getBody().get("problems");
        assertThat(problems).extracting(problem -> problem.get("line") + " " + problem.get("message"))
                .containsExactly("3 用户名已存在", "4 不能分配超出自己权限的角色：导入测试模型员");
        var refused = call(admin, HttpMethod.POST, "/api/users/import", Map.of("rows", bad,
                "password", "Welcome-2026"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login("import03", "Welcome-2026")).isNull();
        assertThat(login("import04", "Welcome-2026")).isNull();

        createUser(root, "importer02", createRole(root, "导入测试无导入权限",
                List.of(IdentityPermissions.USER_VIEW, IdentityPermissions.USER_CREATE)));
        String staff = login("importer02", "member-password");
        assertThat(isPermissionDenied(call(staff, HttpMethod.POST, "/api/users/import",
                Map.of("rows", List.of(row(2, "import05", "无权导入", staffName)), "password", "Welcome-2026"))))
                .isTrue();
        assertThat(login("import05", "Welcome-2026")).isNull();
    }

    private static Map<String, Object> row(int line, String username, String displayName, String roleName) {
        return Map.of("line", line, "username", username, "displayName", displayName, "roleNames", List.of(roleName));
    }

    private Map<String, Object> importRow(int line, String username, long roleId) {
        return Map.of("line", line, "username", username, "displayName", username, "roleNames",
                List.of(roleName(rootToken(), roleId)));
    }

    @SuppressWarnings("unchecked")
    private String roleName(String root, long roleId) {
        var raw = http.exchange("/api/roles", HttpMethod.GET, new HttpEntity<>(bearer(root)), String.class);
        List<Map<String, Object>> roles = JSON.readValue(raw.getBody(), List.class);
        return roles.stream().filter(role -> ((Number) role.get("id")).longValue() == roleId)
                .map(role -> (String) role.get("name")).findFirst().orElseThrow();
    }

    private static HttpHeaders bearer(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** Sa-Token 判定没有权限点时返回 403 且 code 为 FORBIDDEN；业务上的越权（IDENTITY_ESCALATION）不算。 */
    private static boolean isPermissionDenied(ResponseEntity<Map<String, Object>> response) {
        return response.getStatusCode() == HttpStatus.FORBIDDEN && response.getBody() != null
                && "FORBIDDEN".equals(response.getBody().get("code"));
    }

    private static void assertEscalation(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "IDENTITY_ESCALATION");
    }

    private String userWith(String root, List<String> permissions) {
        long role = createRole(root, "测试角色" + (++sequence), permissions);
        var username = "perm" + sequence;
        createUser(root, username, role);
        return login(username, "member-password");
    }

    private long createRole(String root, String name, List<String> permissions) {
        var created = call(root, HttpMethod.POST, "/api/roles", Map.of("name", name, "permissions", permissions));
        assertThat(created.getStatusCode()).as("新建角色 %s", name).isEqualTo(HttpStatus.CREATED);
        return id(created);
    }

    private long createUser(String root, String username, long roleId) {
        var created = call(root, HttpMethod.POST, "/api/users", Map.of("username", username, "displayName", username,
                "password", "member-password", "roleIds", List.of(roleId)));
        assertThat(created.getStatusCode()).as("新建账号 %s", username).isEqualTo(HttpStatus.CREATED);
        return id(created);
    }

    private String rootToken() {
        return TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
    }

    /** 登录成功返回令牌，失败返回 null。 */
    private String login(String username, String password) {
        var response = rawLogin(username, password);
        return response.getStatusCode() == HttpStatus.OK ? (String) response.getBody().get("token") : null;
    }

    private ResponseEntity<Map<String, Object>> rawLogin(String username, String password) {
        return call(null, HttpMethod.POST, "/api/auth/login", Map.of("username", username, "password", password));
    }

    private ResponseEntity<Map<String, Object>> changePassword(String token, String oldPassword, String newPassword) {
        return call(token, HttpMethod.POST, "/api/auth/password",
                Map.of("oldPassword", oldPassword, "newPassword", newPassword));
    }

    private static long id(ResponseEntity<Map<String, Object>> response) {
        return ((Number) response.getBody().get("id")).longValue();
    }

    private static LinkedMultiValueMap<String, Object> file(String filename) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource("收银台交班时要清点备用金。".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return body;
    }

    /** 响应体按 Map 读；返回数组或空响应时 body 为 null，只看状态码。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map<String, Object>> send(String token, HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body instanceof LinkedMultiValueMap) {
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        }
        ResponseEntity<String> raw = http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        Map<String, Object> parsed = null;
        if (raw.getBody() != null && raw.getBody().startsWith("{")) {
            parsed = JSON.readValue(raw.getBody(), Map.class);
        }
        return new ResponseEntity(parsed, raw.getHeaders(), raw.getStatusCode());
    }

    private ResponseEntity<Map<String, Object>> call(String token, HttpMethod method, String path, Object body) {
        return send(token, method, path, body);
    }
}
