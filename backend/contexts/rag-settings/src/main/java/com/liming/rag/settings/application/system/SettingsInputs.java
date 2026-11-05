package com.liming.rag.settings.application.system;

import com.liming.rag.settings.application.SettingKey;

import com.liming.rag.settings.application.SettingsException;

import com.liming.rag.settings.application.model.ModelPurpose;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.Enums;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;

/** 接口传来的原始值在应用层解析，不合规时抛 {@link SettingsException}；web 只转交原始值（#136 B4）。 */
public final class SettingsInputs {

    private SettingsInputs() {
    }

    public static SettingKey key(String value) {
        return Enums.parse(SettingKey.class, value,
                () -> new SettingsException(SettingsException.Reason.NOT_FOUND, "没有这项设置"));
    }

    public static ModelPurpose usage(String value) {
        return Enums.parse(ModelPurpose.class, value, () -> new SettingsException(SettingsException.Reason.INVALID,
                "用途只能是 ANSWER、HEDGE、LIGHT 或 RERANK"));
    }

    /** 文字项要字符串，推荐问题要字符串数组；格式不对时按不合格处理。 */
    public static List<String> value(SettingKey key, Object value) {
        if (!key.list() && value instanceof String text) {
            return List.of(text);
        }
        if (key.list() && value instanceof List<?> items && items.stream().allMatch(String.class::isInstance)) {
            return items.stream().map(String.class::cast).toList();
        }
        throw new SettingsException(SettingsException.Reason.INVALID, "设置值的格式不对");
    }

    /** 变更记录里写的操作人：账号的显示名，查不到时写账号 id。 */
    public static String actorName(UserDirectory users, TenantId tenantId, UserId actor) {
        return users.summaries(tenantId, List.of(actor.value())).values().stream().findFirst()
                .map(UserDirectory.UserSummary::displayName).orElse("账号 " + actor.value());
    }
}
