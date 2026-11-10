package com.liming.rag.settings.application.pack;

import com.liming.rag.settings.application.SettingKey;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** 设置值的校验规则，界面保存和行业包文件共用；与前端 features/settings/api.ts 的 settingProblem 一致。 */
public final class SettingRules {

    private SettingRules() {
    }

    /** 不合格时返回给管理员看的原因。文字项是只有一个元素的列表。 */
    public static Optional<String> problem(SettingKey key, List<String> value) {
        return key.list() ? listProblem(key, value) : textProblem(key, value);
    }

    private static Optional<String> textProblem(SettingKey key, List<String> value) {
        if (value.size() != 1 || value.getFirst() == null || value.getFirst().isBlank()) {
            return Optional.of("不能为空；要用默认值请恢复默认");
        }
        return value.getFirst().strip().length() > key.maxLength()
                ? Optional.of("最多 " + key.maxLength() + " 个字") : Optional.empty();
    }

    private static Optional<String> listProblem(SettingKey key, List<String> value) {
        if (value.size() > SettingKey.MAX_QUESTIONS) {
            return Optional.of("最多 " + SettingKey.MAX_QUESTIONS + " 个问题");
        }
        var seen = new HashSet<String>();
        for (var item : value) {
            if (item == null || item.isBlank()) {
                return Optional.of("有空的问题，填上或删掉");
            }
            var trimmed = item.strip();
            if (trimmed.length() > key.maxLength() || trimmed.contains("\n")) {
                return Optional.of("每个问题最多 " + key.maxLength() + " 个字，不能换行");
            }
            if (!seen.add(trimmed)) {
                return Optional.of("有重复的问题");
            }
        }
        return Optional.empty();
    }
}
