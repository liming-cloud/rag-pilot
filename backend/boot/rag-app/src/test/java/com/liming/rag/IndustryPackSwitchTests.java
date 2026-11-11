package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.settings.api.AnsweringSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 换用测试资源里的另一个行业包（industry-packs/demo）：提示词角色、拒答话术、问答页文案、推荐问题、词表都跟着变，
 * 品牌用部署配置（#122、D-045）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.settings.industry-pack=demo",
        "rag.settings.branding.name=物业知识库",
        "rag.settings.stats-zone=Asia/Singapore"})
@AutoConfigureTestRestTemplate
class IndustryPackSwitchTests {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AnsweringSettings settings;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("问答用的角色、词表、拒答话术和统计时区来自 demo 行业包和部署配置")
    @SuppressWarnings("unchecked")
    void answeringUsesDemoPack() {
        assertThat(settings.pack().answerRole()).isEqualTo("你是物业公司的客服助手，回答物业员工的问题。");
        assertThat(settings.pack().vocabulary()).singleElement()
                .satisfies(term -> assertThat(term.members()).contains("直梯"));

        // 问答只有会话接口：先有一个可用的空知识库，检索不到资料才会用拒答话术回复（#145）。
        http.postForEntity("/api/knowledge-bases", Map.of("name", "拒答话术测试库"), Map.class);
        var refused = TestAsk.ask(http, null, null, "员工年假怎么申请");
        assertThat(refused).containsEntry("refused", true).containsEntry("text", "资料里没有找到依据，请联系物业经理确认。");
        assertThat(http.getForEntity("/api/answers/stats?days=7", Map.class).getBody())
                .containsEntry("zone", "Asia/Singapore");
    }

    @Test
    @DisplayName("问答页文案、推荐问题来自 demo 行业包；品牌来自部署配置")
    @SuppressWarnings("unchecked")
    void textsUseDemoPack() {
        assertThat(http.getForEntity("/api/settings/chat", Map.class).getBody())
                .containsEntry("placeholder", "问问物业服务规范…")
                .containsEntry("suggestedQuestions", List.of("电梯多久保养一次？"))
                .containsEntry("refusalTips", List.of("说出具体的设备名，比如“电梯”“水泵”。"))
                .containsEntry("refusalContact", "还是找不到的话，请联系物业经理确认。");
        assertThat(http.getForEntity("/api/auth/branding", Map.class).getBody()).containsEntry("name", "物业知识库");
        var overview = http.getForEntity("/api/settings", Map.class).getBody();
        assertThat(((Map<?, ?>) overview.get("industryPack")).get("name")).isEqualTo("物业示例");
        assertThat(String.valueOf(overview.get("items"))).contains("source=ENVIRONMENT");
    }
}
