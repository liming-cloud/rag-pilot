package com.liming.rag.settings.application.system;

import com.liming.rag.settings.application.SettingKey;

import com.liming.rag.settings.application.pack.KernelDefaults;

import com.liming.rag.settings.application.SettingsException;
import com.liming.rag.settings.application.SettingsProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.settings.application.system.SettingSource;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 系统设置的取值来源、保存校验和恢复默认（D-045）。 */
class SystemSettingsServiceTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final UserId ADMIN = new UserId(1);

    private final MemoryStore store = new MemoryStore();
    /** 记下的审计：每条是“操作 说明”（#145 C2）。 */
    private final List<String> audited = new java.util.ArrayList<>();

    private SystemSettingsService service(String pack, SettingsProperties.Branding branding) {
        return new SystemSettingsService(store, new OneUser(),
                new SettingsProperties("", pack, branding, "Asia/Shanghai"), Clock.systemUTC(),
                (tenantId, actor, action, targetType, targetId, detail) -> audited.add(action + " " + detail));
    }

    private SystemSettingsService retail() {
        return service("retail", new SettingsProperties.Branding("", "", ""));
    }

    @Test
    @DisplayName("没保存过时：品牌用部署配置，没配置的用内核默认值；问答页文案和拒答话术用行业包")
    void defaultsBySource() {
        var settings = service("retail", new SettingsProperties.Branding("示例知识库", "", ""));

        assertThat(settings.branding()).isEqualTo(new BrandingInfo("示例知识库",
                KernelDefaults.BRAND_TAGLINE, KernelDefaults.LOGIN_HINT));
        var items = items(settings);
        assertThat(items.get(SettingKey.BRAND_NAME).source()).isEqualTo(SettingSource.ENVIRONMENT);
        assertThat(items.get(SettingKey.BRAND_TAGLINE).source()).isEqualTo(SettingSource.KERNEL);
        assertThat(items.get(SettingKey.CHAT_PLACEHOLDER).source()).isEqualTo(SettingSource.INDUSTRY_PACK);
        assertThat(settings.refusalMessage()).contains("联系店长确认");
        assertThat(settings.chatTexts().suggestedQuestions()).isNotEmpty();
    }

    @Test
    @DisplayName("行业包也没写时用内核默认值：没有推荐问题，拒答话术不带行业内容")
    void kernelDefaultsWithoutPackContent() {
        var settings = service("bare", new SettingsProperties.Branding("", "", ""));

        assertThat(settings.refusalMessage()).isEqualTo(KernelDefaults.REFUSAL_MESSAGE);
        assertThat(settings.chatTexts()).isEqualTo(new ChatTexts(KernelDefaults.CHAT_PLACEHOLDER,
                KernelDefaults.CHAT_EMPTY_HINT, List.of()));
        assertThat(items(settings).get(SettingKey.REFUSAL_MESSAGE).source()).isEqualTo(SettingSource.KERNEL);
    }

    @Test
    @DisplayName("保存后以界面为准，记下谁改的；恢复默认后回到行业包的值")
    void savesAndRestores() {
        var settings = retail();

        var saved = settings.save(TENANT, ADMIN, SettingKey.REFUSAL_MESSAGE, List.of("  资料里没有依据，请联系总部。 "));
        assertThat(saved.value()).containsExactly("资料里没有依据，请联系总部。");
        assertThat(saved.source()).isEqualTo(SettingSource.DATABASE);
        assertThat(saved.defaultSource()).isEqualTo(SettingSource.INDUSTRY_PACK);
        assertThat(saved.updatedBy()).isEqualTo("超级管理员");
        assertThat(settings.refusalMessage()).isEqualTo("资料里没有依据，请联系总部。");

        var restored = settings.restore(TENANT, ADMIN, SettingKey.REFUSAL_MESSAGE);
        assertThat(restored.source()).isEqualTo(SettingSource.INDUSTRY_PACK);
        assertThat(audited).as("保存和恢复默认都记审计（#145 C2）").containsExactly(
                "SETTING_CHANGED 修改系统设置 REFUSAL_MESSAGE：资料里没有依据，请联系总部。",
                "SETTING_RESTORED 系统设置 REFUSAL_MESSAGE 恢复为默认值");
        assertThat(settings.refusalMessage()).contains("联系店长确认");
    }

    @Test
    @DisplayName("推荐问题保持顺序，可以清空；空的、重复的、超过 4 个或超长的不能保存，库里不留")
    void validatesSuggestedQuestions() {
        var settings = retail();

        settings.save(TENANT, ADMIN, SettingKey.SUGGESTED_QUESTIONS, List.of("第二个问题？", " 第一个问题？"));
        assertThat(settings.chatTexts().suggestedQuestions()).containsExactly("第二个问题？", "第一个问题？");
        settings.save(TENANT, ADMIN, SettingKey.SUGGESTED_QUESTIONS, List.of());
        assertThat(settings.chatTexts().suggestedQuestions()).isEmpty();

        for (var bad : List.of(List.of("问题？", " "), List.of("问题？", "问题？"), List.of("一", "二", "三", "四", "五"),
                List.of("长".repeat(41)), List.of("两行\n问题"))) {
            assertThatThrownBy(() -> settings.save(TENANT, ADMIN, SettingKey.SUGGESTED_QUESTIONS, bad))
                    .isInstanceOf(SettingsException.class).hasMessageStartingWith("推荐问题：");
        }
        assertThat(settings.chatTexts().suggestedQuestions()).isEmpty();
    }

    @Test
    @DisplayName("文字项不能为空、不能超长，不合格时不写库")
    void validatesTexts() {
        var settings = retail();

        assertThatThrownBy(() -> settings.save(TENANT, ADMIN, SettingKey.BRAND_NAME, List.of("  ")))
                .isInstanceOf(SettingsException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> settings.save(TENANT, ADMIN, SettingKey.BRAND_NAME, List.of("名".repeat(31))))
                .isInstanceOf(SettingsException.class).hasMessageContaining("最多 30 个字");
        assertThat(store.saved).isEmpty();
        assertThat(audited).as("不合格的保存不记审计").isEmpty();
    }

    private static Map<SettingKey, SettingItem> items(SystemSettingsService settings) {
        var items = new EnumMap<SettingKey, SettingItem>(SettingKey.class);
        settings.overview().items().forEach(item -> items.put(item.key(), item));
        return items;
    }

    private static final class MemoryStore implements SystemSettingsStore {

        private final Map<SettingKey, Saved> saved = new EnumMap<>(SettingKey.class);

        @Override
        public Map<SettingKey, Saved> all() {
            return Map.copyOf(saved);
        }

        @Override
        public void save(SettingKey key, List<String> value, long updatedBy, String updatedByName,
                OffsetDateTime updatedAt) {
            saved.put(key, new Saved(key, List.copyOf(value), updatedByName, updatedAt));
        }

        @Override
        public void delete(SettingKey key) {
            saved.remove(key);
        }
    }

    private static final class OneUser implements UserDirectory {

        @Override
        public boolean exists(TenantId tenantId, UserId userId) {
            return true;
        }

        @Override
        public List<Long> roleIds(TenantId tenantId, UserId userId) {
            return List.of();
        }

        @Override
        public boolean isSuperAdmin(TenantId tenantId, UserId userId) {
            return true;
        }

        @Override
        public boolean roleExists(TenantId tenantId, long roleId) {
            return true;
        }

        @Override
        public Map<Long, UserSummary> summaries(TenantId tenantId, Collection<Long> userIds) {
            return Map.of(1L, new UserSummary(1, "admin", "超级管理员"));
        }
    }
}
