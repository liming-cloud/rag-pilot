package com.liming.rag.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 系统设置的部署配置：品牌（D-042，从 identity 迁来）、行业包、统计时区（D-045）。 */
class SettingsPropertiesTest {

    @Test
    @DisplayName("没有配置或配置留空时，品牌三项为空（用内核默认值），行业包是 retail，时区是北京时间")
    void defaultsWhenMissingOrBlank() {
        for (var source : List.of(Map.<String, String>of(), Map.of("rag.settings.branding.name", "  ",
                "rag.settings.branding.tagline", "", "rag.settings.branding.login-hint", ""))) {
            var properties = bind(source);
            assertThat(properties.branding().name()).isEmpty();
            assertThat(properties.branding().tagline()).isEmpty();
            assertThat(properties.branding().loginHint()).isEmpty();
            assertThat(properties.industryPack()).isEqualTo("retail");
            assertThat(properties.statsZone()).isEqualTo("Asia/Shanghai");
        }
    }

    @Test
    @DisplayName("按 rag.settings.branding 绑定，去掉首尾空格")
    void bindsConfiguredValues() {
        var branding = bind(Map.of("rag.settings.branding.name", " 示例知识库 ",
                "rag.settings.branding.login-hint", "账号由总部开通。")).branding();

        assertThat(branding.name()).isEqualTo("示例知识库");
        assertThat(branding.loginHint()).isEqualTo("账号由总部开通。");
        assertThat(branding.tagline()).isEmpty();
    }

    @Test
    @DisplayName("品牌超过长度、行业包名或时区不合法时启动失败")
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> bind(Map.of("rag.settings.branding.name", "名".repeat(31))))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("rag.settings.branding.name");
        assertThatThrownBy(() -> bind(Map.of("rag.settings.industry-pack", "../retail")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("rag.settings.industry-pack");
        assertThatThrownBy(() -> bind(Map.of("rag.settings.stats-zone", "Mars/Base")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("rag.settings.stats-zone");
    }

    private static SettingsProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("rag.settings", SettingsProperties.class);
    }
}
