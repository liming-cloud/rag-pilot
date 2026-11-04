package com.liming.rag.settings.application;

import com.liming.rag.ai.ModelUsage;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Locale;

/** 接口传来的原始值在应用层解析，不合规时抛 {@link SettingsException}；web 只转交原始值（#136 B4）。 */
final class SettingsInputs {

    private SettingsInputs() {
    }

    static SettingKey key(String value) {
        try {
            return SettingKey.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SettingsException(SettingsException.Reason.NOT_FOUND, "没有这项设置");
        }
    }

    static ModelUsage usage(String value) {
        try {
            return ModelUsage.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SettingsException(SettingsException.Reason.INVALID, "用途只能是 ANSWER、HEDGE、LIGHT 或 RERANK");
        }
    }

    /** 文字项要字符串，推荐问题要字符串数组；格式不对时按不合格处理。 */
    static List<String> value(SettingKey key, Object value) {
        if (!key.list() && value instanceof String text) {
            return List.of(text);
        }
        if (key.list() && value instanceof List<?> items && items.stream().allMatch(String.class::isInstance)) {
            return items.stream().map(String.class::cast).toList();
        }
        throw new SettingsException(SettingsException.Reason.INVALID, "设置值的格式不对");
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 变更记录里写的操作人：账号的显示名，查不到时写账号 id。 */
    static String actorName(UserDirectory users, TenantId tenantId, UserId actor) {
        return users.summaries(tenantId, List.of(actor.value())).values().stream().findFirst()
                .map(UserDirectory.UserSummary::displayName).orElse("账号 " + actor.value());
    }
}
