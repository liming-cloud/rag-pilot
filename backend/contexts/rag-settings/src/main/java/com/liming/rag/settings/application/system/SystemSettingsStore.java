package com.liming.rag.settings.application.system;

import com.liming.rag.settings.application.SettingKey;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 系统设置的存储端口：只存在界面保存过的项。 */
public interface SystemSettingsStore {

    /** 界面保存过的一项。列表按顺序保存。 */
    record Saved(SettingKey key, List<String> value, String updatedByName, OffsetDateTime updatedAt) {
    }

    Map<SettingKey, Saved> all();

    /** 没保存过时插入，保存过时覆盖。 */
    void save(SettingKey key, List<String> value, long updatedBy, String updatedByName, OffsetDateTime updatedAt);

    void delete(SettingKey key);
}
