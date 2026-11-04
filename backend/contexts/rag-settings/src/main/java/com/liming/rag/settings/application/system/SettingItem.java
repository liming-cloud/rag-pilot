package com.liming.rag.settings.application.system;

import com.liming.rag.settings.application.SettingKey;
import java.time.OffsetDateTime;
import java.util.List;

/** 一项设置；文字项的 value 只有一个元素。 */
public record SettingItem(SettingKey key, List<String> value, SettingSource source, List<String> defaultValue,
        SettingSource defaultSource, String updatedBy, OffsetDateTime updatedAt) {
}
