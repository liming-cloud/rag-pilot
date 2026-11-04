package com.liming.rag.settings.infrastructure.persistence;

import com.liming.rag.settings.application.SettingKey;
import com.liming.rag.settings.application.system.SystemSettingsStore;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** 列表项（推荐问题）一行一个存成一段文字；空列表存空串。 */
@Repository
class MybatisSystemSettingsStore implements SystemSettingsStore {

    private final SystemSettingMapper mapper;

    MybatisSystemSettingsStore(SystemSettingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<SettingKey, Saved> all() {
        var saved = new EnumMap<SettingKey, Saved>(SettingKey.class);
        for (var row : mapper.all()) {
            var key = SettingKey.valueOf(row.settingKey);
            var text = row.value;
            List<String> value = !key.list() ? List.of(text) : text.isEmpty() ? List.of() : text.lines().toList();
            saved.put(key, new Saved(key, value, row.updatedByName, row.updatedAt));
        }
        return saved;
    }

    @Override
    public void save(SettingKey key, List<String> value, long updatedBy, String updatedByName,
            OffsetDateTime updatedAt) {
        mapper.save(key.name(), String.join("\n", value), updatedBy, updatedByName, updatedAt);
    }

    @Override
    public void delete(SettingKey key) {
        mapper.delete(key.name());
    }

}
