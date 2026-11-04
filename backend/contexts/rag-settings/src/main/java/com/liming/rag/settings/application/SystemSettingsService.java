package com.liming.rag.settings.application;

import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.settings.api.AnsweringSettings;
import com.liming.rag.settings.api.IndustryPack;
import com.liming.rag.settings.api.StatsZone;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统设置（D-045）：行业包启动时读一次；能在界面改的项保存后以界面为准，没保存过时品牌用部署配置、其余用行业包，
 * 都没有时用内核默认值。每次读都查一次库，保存后不用重启。同时给问答提供行业包、拒答话术和统计时区。
 */
@Service
public class SystemSettingsService implements AnsweringSettings, StatsZone {

    /** 当前值来自哪里。 */
    public enum Source {
        DATABASE, ENVIRONMENT, INDUSTRY_PACK, KERNEL
    }

    /** 一项设置；文字项的 value 只有一个元素。 */
    public record Item(SettingKey key, List<String> value, Source source, List<String> defaultValue,
            Source defaultSource, String updatedBy, OffsetDateTime updatedAt) {
    }

    /** 系统设置页的全部内容。 */
    public record Overview(IndustryPack pack, String statsZone, List<Item> items) {
    }

    /** 登录页、顶栏显示的品牌。 */
    public record Branding(String name, String tagline, String loginHint) {
    }

    /** 问答页的文案和推荐问题。 */
    public record ChatTexts(String placeholder, String emptyHint, List<String> suggestedQuestions) {
    }

    private record Fallback(List<String> value, Source source) {
    }

    private final SystemSettingsStore store;
    private final UserDirectory users;
    private final IndustryPackFile file;
    private final IndustryPack pack;
    private final SettingsProperties.Branding branding;
    private final ZoneId statsZone;
    private final Clock clock;

    SystemSettingsService(SystemSettingsStore store, UserDirectory users, SettingsProperties properties, Clock clock) {
        this.store = store;
        this.users = users;
        this.file = IndustryPackFile.load(properties.industryPack());
        this.pack = file.pack(properties.industryPack());
        this.branding = properties.branding();
        this.statsZone = ZoneId.of(properties.statsZone());
        this.clock = clock;
    }

    @Override
    public IndustryPack pack() {
        return pack;
    }

    @Override
    @Transactional(readOnly = true)
    public String refusalMessage() {
        return text(store.all(), SettingKey.REFUSAL_MESSAGE);
    }

    @Override
    public ZoneId statsZone() {
        return statsZone;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        var saved = store.all();
        return new Overview(pack, statsZone.getId(),
                Arrays.stream(SettingKey.values()).map(key -> item(saved, key)).toList());
    }

    @Transactional(readOnly = true)
    public Branding branding() {
        var saved = store.all();
        return new Branding(text(saved, SettingKey.BRAND_NAME), text(saved, SettingKey.BRAND_TAGLINE),
                text(saved, SettingKey.LOGIN_HINT));
    }

    @Transactional(readOnly = true)
    public ChatTexts chatTexts() {
        var saved = store.all();
        return new ChatTexts(text(saved, SettingKey.CHAT_PLACEHOLDER), text(saved, SettingKey.CHAT_EMPTY_HINT),
                item(saved, SettingKey.SUGGESTED_QUESTIONS).value());
    }

    /** 按接口传来的原始值保存：{@code key} 是设置项的名字，{@code value} 文字项是字符串、推荐问题是字符串数组。 */
    @Transactional
    public Item save(TenantId tenantId, UserId actor, String key, Object value) {
        var settingKey = SettingsInputs.key(key);
        return save(tenantId, actor, settingKey, SettingsInputs.value(settingKey, value));
    }

    /** 保存一项；文字去掉首尾空格，推荐问题去掉每条的首尾空格、保持顺序。不合格时抛 INVALID。 */
    Item save(TenantId tenantId, UserId actor, SettingKey key, List<String> value) {
        if (value == null) {
            throw invalid(key, "不能为空");
        }
        SettingRules.problem(key, value).ifPresent(problem -> {
            throw invalid(key, problem);
        });
        store.save(key, value.stream().map(String::strip).toList(), actor.value(),
                SettingsInputs.actorName(users, tenantId, actor), OffsetDateTime.now(clock));
        return item(store.all(), key);
    }

    /** 删掉界面保存的值，回到部署配置、行业包或内核的默认值。没保存过时什么也不做。 */
    @Transactional
    public Item restore(String key) {
        return restore(SettingsInputs.key(key));
    }

    Item restore(SettingKey key) {
        store.delete(key);
        return item(store.all(), key);
    }

    private Item item(Map<SettingKey, SystemSettingsStore.Saved> saved, SettingKey key) {
        var fallback = fallback(key);
        var stored = saved.get(key);
        return stored == null
                ? new Item(key, fallback.value(), fallback.source(), fallback.value(), fallback.source(), null, null)
                : new Item(key, stored.value(), Source.DATABASE, fallback.value(), fallback.source(),
                        stored.updatedByName(), stored.updatedAt());
    }

    private String text(Map<SettingKey, SystemSettingsStore.Saved> saved, SettingKey key) {
        return item(saved, key).value().getFirst();
    }

    /** 没在界面保存过时的值：品牌看部署配置，其余看行业包，都没有时用内核默认值。 */
    private Fallback fallback(SettingKey key) {
        if (key.brand()) {
            var configured = branding.of(key);
            return configured.isEmpty() ? new Fallback(KernelDefaults.of(key), Source.KERNEL)
                    : new Fallback(List.of(configured), Source.ENVIRONMENT);
        }
        return file.value(key).map(value -> new Fallback(value, Source.INDUSTRY_PACK))
                .orElseGet(() -> new Fallback(KernelDefaults.of(key), Source.KERNEL));
    }

    private static SettingsException invalid(SettingKey key, String problem) {
        return new SettingsException(SettingsException.Reason.INVALID, label(key) + "：" + problem);
    }

    private static String label(SettingKey key) {
        return switch (key) {
            case BRAND_NAME -> "产品名称";
            case BRAND_TAGLINE -> "一句话介绍";
            case LOGIN_HINT -> "登录提示";
            case CHAT_PLACEHOLDER -> "输入框占位";
            case CHAT_EMPTY_HINT -> "空状态说明";
            case SUGGESTED_QUESTIONS -> "推荐问题";
            case REFUSAL_MESSAGE -> "拒答话术";
        };
    }
}
