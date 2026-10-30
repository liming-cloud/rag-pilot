package com.liming.rag.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 产品名称和登录页提示的配置（D-042）。 */
class IdentityPropertiesTest {

    @Test
    @DisplayName("没有配置或配置留空时用内核默认值")
    void defaultsWhenMissingOrBlank() {
        for (var source : java.util.List.of(Map.<String, String>of(), Map.of("rag.identity.branding.name", "  ",
                "rag.identity.branding.tagline", "", "rag.identity.branding.login-hint", ""))) {
            var branding = bind(source).branding();
            assertThat(branding.name()).isEqualTo(IdentityProperties.Branding.DEFAULT_NAME);
            assertThat(branding.tagline()).isEqualTo(IdentityProperties.Branding.DEFAULT_TAGLINE);
            assertThat(branding.loginHint()).isEqualTo(IdentityProperties.Branding.DEFAULT_LOGIN_HINT);
        }
    }

    @Test
    @DisplayName("按 rag.identity.branding 绑定，去掉首尾空格")
    void bindsConfiguredValues() {
        var branding = bind(Map.of("rag.identity.branding.name", " 门店运营知识库 ",
                "rag.identity.branding.login-hint", "账号由店长开通。")).branding();

        assertThat(branding.name()).isEqualTo("门店运营知识库");
        assertThat(branding.loginHint()).isEqualTo("账号由店长开通。");
        assertThat(branding.tagline()).isEqualTo(IdentityProperties.Branding.DEFAULT_TAGLINE);
    }

    @Test
    @DisplayName("超过长度时启动失败")
    void rejectsTooLong() {
        assertThatThrownBy(() -> bind(Map.of("rag.identity.branding.name", "名".repeat(31))))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("rag.identity.branding.name");
    }

    private static IdentityProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("rag.identity", IdentityProperties.class);
    }
}
