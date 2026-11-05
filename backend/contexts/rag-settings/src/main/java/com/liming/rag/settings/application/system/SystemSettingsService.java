package com.liming.rag.settings.application.system;

import com.liming.rag.settings.application.model.Overview;

import com.liming.rag.settings.application.SettingKey;

import com.liming.rag.settings.application.pack.SettingRules;
import com.liming.rag.settings.application.pack.KernelDefaults;

import com.liming.rag.settings.application.pack.IndustryPackFile;
import com.liming.rag.settings.application.SettingsException;
import com.liming.rag.settings.application.SettingsProperties;

import com.liming.rag.identity.api.AuditTrail;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统设置（D-045）：行业包启动时读一次；能在界面改的项保存后以界面为准，没保存过时品牌用部署配置、其余用行业包，
 * 都没有时用内核默认值。界面保存的值整份缓存（D-053），保存、恢复后在提交后清除，不用重启。同时给问答提供行业包、
 * 拒答话术和统计时区。
 */
@Service
public class SystemSettingsService implements AnsweringSettings, StatsZone {

    private record Fallback(List<String> value, SettingSource source) {
    }


    private final SystemSettingsStore store;
    private final AuditTrail audit;
    private final UserDirectory users;
    private final IndustryPackFile file;
    private final IndustryPack pack;
    private final SettingsProperties.Branding branding;
    private final ZoneId statsZone;
    private final Clock clock;
    private final SavedSettingsCache cache;

    SystemSettingsService(SystemSettingsStore store, UserDirectory users, SettingsProperties properties, Clock clock,
            AuditTrail audit, SavedSettingsCache cache) {
        this.audit = audit;
        this.store = store;
        this.users = users;
        this.file = IndustryPackFile.load(properties.industryPack());
        this.pack = file.pack(properties.industryPack());
        this.branding = properties.branding();
        this.statsZone = ZoneId.of(properties.statsZone());
        this.clock = clock;
        this.cache = cache;
    }

    @Override
    public IndustryPack pack() {
        return pack;
    }

    @Override
    public String refusalMessage() {
        return text(saved(), SettingKey.REFUSAL_MESSAGE);
    }

    @Override
    public ZoneId statsZone() {
        return statsZone;
    }

    public SettingsOverview overview() {
        var saved = saved();
        return new SettingsOverview(pack, statsZone.getId(),
                Arrays.stream(SettingKey.values()).map(key -> item(saved, key)).toList());
    }

    public BrandingInfo branding() {
        var saved = saved();
        return new BrandingInfo(text(saved, SettingKey.BRAND_NAME), text(saved, SettingKey.BRAND_TAGLINE),
                text(saved, SettingKey.LOGIN_HINT));
    }

    public ChatTexts chatTexts() {
        var saved = saved();
        return new ChatTexts(text(saved, SettingKey.CHAT_PLACEHOLDER), text(saved, SettingKey.CHAT_EMPTY_HINT),
                item(saved, SettingKey.SUGGESTED_QUESTIONS).value());
    }

    /** 按接口传来的原始值保存：{@code key} 是设置项的名字，{@code value} 文字项是字符串、推荐问题是字符串数组。 */
    @Transactional
    public SettingItem save(TenantId tenantId, UserId actor, String key, Object value) {
        var settingKey = SettingsInputs.key(key);
        return save(tenantId, actor, settingKey, SettingsInputs.value(settingKey, value));
    }

    /** 保存一项；文字去掉首尾空格，推荐问题去掉每条的首尾空格、保持顺序。不合格时抛 INVALID。 */
    SettingItem save(TenantId tenantId, UserId actor, SettingKey key, List<String> value) {
        if (value == null) {
            throw invalid(key, "不能为空");
        }
        SettingRules.problem(key, value).ifPresent(problem -> {
            throw invalid(key, problem);
        });
        var stripped = value.stream().map(String::strip).toList();
        store.save(key, stripped, actor.value(), SettingsInputs.actorName(users, tenantId, actor), OffsetDateTime.now(clock));
        audit.record(tenantId, actor, "SETTING_CHANGED", "SETTING", key.name(),
                "修改系统设置 " + key.name() + "：" + String.join(" / ", stripped));
        cache.forget();
        return item(store.all(), key);
    }

    /** 删掉界面保存的值，回到部署配置、行业包或内核的默认值。没保存过时什么也不做。 */
    @Transactional
    public SettingItem restore(TenantId tenantId, UserId actor, String key) {
        return restore(tenantId, actor, SettingsInputs.key(key));
    }

    SettingItem restore(TenantId tenantId, UserId actor, SettingKey key) {
        store.delete(key);
        cache.forget();
        audit.record(tenantId, actor, "SETTING_RESTORED", "SETTING", key.name(), "系统设置 " + key.name() + " 恢复为默认值");
        return item(store.all(), key);
    }

    /** 界面保存过的值，读缓存；一条语句读出，不用开事务。 */
    private Map<SettingKey, SystemSettingsStore.Saved> saved() {
        return cache.get(() -> List.copyOf(store.all().values())).stream()
                .collect(Collectors.toMap(SystemSettingsStore.Saved::key, Function.identity()));
    }

    private SettingItem item(Map<SettingKey, SystemSettingsStore.Saved> saved, SettingKey key) {
        var fallback = fallback(key);
        var stored = saved.get(key);
        return stored == null
                ? new SettingItem(key, fallback.value(), fallback.source(), fallback.value(), fallback.source(), null, null)
                : new SettingItem(key, stored.value(), SettingSource.DATABASE, fallback.value(), fallback.source(),
                        stored.updatedByName(), stored.updatedAt());
    }

    private String text(Map<SettingKey, SystemSettingsStore.Saved> saved, SettingKey key) {
        return item(saved, key).value().getFirst();
    }

    /** 没在界面保存过时的值：品牌看部署配置，其余看行业包，都没有时用内核默认值。 */
    private Fallback fallback(SettingKey key) {
        if (key.brand()) {
            var configured = branding.of(key);
            return configured.isEmpty() ? new Fallback(KernelDefaults.of(key), SettingSource.KERNEL)
                    : new Fallback(List.of(configured), SettingSource.ENVIRONMENT);
        }
        return file.value(key).map(value -> new Fallback(value, SettingSource.INDUSTRY_PACK))
                .orElseGet(() -> new Fallback(KernelDefaults.of(key), SettingSource.KERNEL));
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
