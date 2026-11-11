package com.liming.rag;

import com.liming.rag.settings.application.system.SavedSettingsCache;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 系统设置（#122、D-045）端到端：默认是新零售行业包；界面保存后不重启就生效（登录页品牌、问答页文案、拒答话术），
 * 恢复默认后回到行业包或内核的值；没有权限的保存被拒绝且不写库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SystemSettingsTests {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SavedSettingsCache settingsCache;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
        jdbc.update("DELETE FROM system_setting");
        settingsCache.forget();
    }

    @Test
    @DisplayName("登录页品牌不用登录就能读，只有展示用的三项；没配置时是内核默认值")
    void brandingIsPublicAndMinimal() {
        var response = anonymous("/api/auth/branding");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("name", "tagline", "loginHint")
                .containsEntry("name", "明答").containsEntry("loginHint", "账号由管理员开通；忘记密码请联系管理员。");
    }

    @Test
    @DisplayName("设置页列出新零售行业包和全部可改项，标出来源；行业包内容只读")
    void overviewShowsSourcesAndPack() {
        var overview = get("/api/settings");

        assertThat(overview).containsEntry("statsZone", "Asia/Shanghai");
        var pack = (Map<?, ?>) overview.get("industryPack");
        assertThat(pack.get("code")).isEqualTo("retail");
        assertThat(pack.get("answerRole")).isEqualTo("你是连锁门店的运营助手，回答门店员工的问题。");
        assertThat(String.valueOf(pack.get("vocabulary"))).contains("鲜食").contains("便当");
        var items = items(overview);
        assertThat(items.get("BRAND_NAME")).containsEntry("source", "KERNEL").containsEntry("kind", "text");
        assertThat(items.get("CHAT_PLACEHOLDER")).containsEntry("source", "INDUSTRY_PACK");
        assertThat(items.get("SUGGESTED_QUESTIONS")).containsEntry("kind", "list");
        assertThat((List<?>) items.get("SUGGESTED_QUESTIONS").get("value")).isNotEmpty();
    }

    @Test
    @DisplayName("保存后不重启就生效：登录页品牌、问答页文案、拒答话术；恢复默认后回到原来的值")
    @SuppressWarnings("unchecked")
    void savedValuesTakeEffectAndRestore() {
        assertThat(save("BRAND_NAME", " 示例知识库 ").getBody()).containsEntry("value", "示例知识库")
                .containsEntry("source", "DATABASE").containsEntry("updatedBy", "超级管理员");
        assertThat(anonymous("/api/auth/branding").getBody()).containsEntry("name", "示例知识库");

        save("SUGGESTED_QUESTIONS", List.of("第一个问题？", "第二个问题？"));
        save("CHAT_PLACEHOLDER", "问问示例…");
        assertThat(get("/api/settings/chat")).containsEntry("placeholder", "问问示例…")
                .containsEntry("suggestedQuestions", List.of("第一个问题？", "第二个问题？"));

        // 问答只有会话接口：先有一个可用的空知识库，检索不到资料才会用拒答话术回复（#145）。
        http.postForEntity("/api/knowledge-bases", Map.of("name", "拒答话术测试库"), Map.class);
        save("REFUSAL_MESSAGE", "资料里没有依据，请联系总部确认。");
        var refused = TestAsk.ask(http, null, null, "员工年假怎么申请");
        assertThat(refused).containsEntry("refused", true).containsEntry("text", "资料里没有依据，请联系总部确认。");

        var restored = http.exchange("/api/settings/REFUSAL_MESSAGE", HttpMethod.DELETE, null, Map.class);
        assertThat(restored.getBody()).containsEntry("source", "INDUSTRY_PACK");
        var again = TestAsk.ask(http, null, null, "员工年假怎么申请");
        assertThat(String.valueOf(again.get("text"))).contains("联系店长确认");
        http.exchange("/api/settings/BRAND_NAME", HttpMethod.DELETE, null, Map.class);
        assertThat(anonymous("/api/auth/branding").getBody()).containsEntry("name", "明答");
    }

    @Test
    @DisplayName("不合格的值、不认识的项、格式不对都被拒绝，库里不留")
    void rejectsInvalidValues() {
        assertThat(save("BRAND_NAME", "名".repeat(31)).getBody()).containsEntry("code", "SETTINGS_INVALID");
        assertThat(save("BRAND_NAME", "  ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(save("SUGGESTED_QUESTIONS", List.of("一？", "一？")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(save("SUGGESTED_QUESTIONS", "不是列表").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(save("NOT_A_SETTING", "x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_setting", Long.class)).isZero();
    }

    @Test
    @DisplayName("员工能读问答页文案，但看不到设置页、不能保存，库里不写")
    void staffCannotChangeSettings() {
        long staffRole = TestLogin.staffRoleId(http);
        http.postForEntity("/api/users", Map.of("username", "settings01", "displayName", "店员", "password",
                "settings-password", "roleIds", List.of(staffRole)), Map.class);
        String staff = TestLogin.token(http, "settings01", "settings-password");

        assertThat(as(staff, HttpMethod.GET, "/api/settings/chat", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(as(staff, HttpMethod.GET, "/api/settings", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(as(staff, HttpMethod.PUT, "/api/settings/BRAND_NAME", Map.of("value", "越权名称"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_setting", Long.class)).isZero();
        assertThat(anonymous("/api/auth/branding").getBody()).containsEntry("name", "明答");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> items(Map<String, Object> overview) {
        var byKey = new java.util.HashMap<String, Map<String, Object>>();
        for (var item : (List<Map<String, Object>>) overview.get("items")) {
            byKey.put((String) item.get("key"), item);
        }
        return byKey;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        var response = http.getForEntity(path, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> save(String key, Object value) {
        ResponseEntity response = http.exchange("/api/settings/" + key, HttpMethod.PUT,
                new HttpEntity<>(Map.of("value", value)), Map.class);
        return response;
    }

    /** 不带令牌的请求：先清掉管理员的默认令牌，读完再登回来。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> anonymous(String path) {
        http.getRestTemplate().setInterceptors(List.of());
        try {
            ResponseEntity response = http.getForEntity(path, Map.class);
            return response;
        } finally {
            TestLogin.asAdmin(http);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> as(String token, HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity response = http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
        return response;
    }
}
