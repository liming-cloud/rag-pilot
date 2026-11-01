package com.liming.rag.settings.infrastructure.persistence;

import com.liming.rag.settings.application.SettingKey;
import com.liming.rag.settings.application.SystemSettingsStore;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
            var key = SettingKey.valueOf((String) row.get("setting_key"));
            var text = (String) row.get("value");
            List<String> value = !key.list() ? List.of(text) : text.isEmpty() ? List.of() : text.lines().toList();
            saved.put(key, new Saved(key, value, (String) row.get("updated_by_name"), time(row.get("updated_at"))));
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

    /** 以 Map 读取时 timestamptz 是 java.sql.Timestamp，换成带时区的时间。 */
    private static OffsetDateTime time(Object value) {
        return value instanceof Timestamp timestamp
                ? timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime()
                : (OffsetDateTime) value;
    }
}
