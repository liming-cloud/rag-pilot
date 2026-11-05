package com.liming.rag.settings.application.system;

import com.liming.rag.settings.application.SettingKey;

import com.liming.rag.settings.application.SettingsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.settings.application.model.ModelPurpose;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettingsInputsTest {

    @Test
    @DisplayName("设置项和用途的名字不分大小写；没有这项设置是 NOT_FOUND，用途不对是 INVALID")
    void parsesNames() {
        assertThat(SettingsInputs.key("brand_name")).isEqualTo(SettingKey.BRAND_NAME);
        assertThat(SettingsInputs.usage("rerank")).isEqualTo(ModelPurpose.RERANK);

        assertThatThrownBy(() -> SettingsInputs.key("nope")).isInstanceOfSatisfying(SettingsException.class,
                e -> assertThat(e.code()).isEqualTo("SETTINGS_NOT_FOUND"));
        assertThatThrownBy(() -> SettingsInputs.key(null)).isInstanceOf(SettingsException.class);
        assertThatThrownBy(() -> SettingsInputs.usage("embedding")).isInstanceOfSatisfying(SettingsException.class,
                e -> assertThat(e.code()).isEqualTo("SETTINGS_INVALID"));
    }

    @Test
    @DisplayName("文字项只收字符串，推荐问题只收字符串数组，其他形状按不合格处理")
    void checksValueShape() {
        assertThat(SettingsInputs.value(SettingKey.BRAND_NAME, "门店助手")).containsExactly("门店助手");
        assertThat(SettingsInputs.value(SettingKey.SUGGESTED_QUESTIONS, List.of("一？", "二？")))
                .containsExactly("一？", "二？");

        for (var bad : new Object[] {null, List.of("门店助手"), 3}) {
            assertThatThrownBy(() -> SettingsInputs.value(SettingKey.BRAND_NAME, bad))
                    .isInstanceOf(SettingsException.class).hasMessage("设置值的格式不对");
        }
        assertThatThrownBy(() -> SettingsInputs.value(SettingKey.SUGGESTED_QUESTIONS, "一？"))
                .isInstanceOf(SettingsException.class).hasMessage("设置值的格式不对");
        assertThatThrownBy(() -> SettingsInputs.value(SettingKey.SUGGESTED_QUESTIONS, List.of("一？", 2)))
                .isInstanceOf(SettingsException.class).hasMessage("设置值的格式不对");
    }
}
