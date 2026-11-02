package com.liming.rag.settings.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** system_setting 表，SQL 写在 {@code mapper/settings/SystemSettingMapper.xml}。 */
@Mapper
interface SystemSettingMapper {

    List<Map<String, Object>> all();

    void save(@Param("key") String key, @Param("value") String value, @Param("updatedBy") long updatedBy,
            @Param("updatedByName") String updatedByName, @Param("updatedAt") OffsetDateTime updatedAt);

    void delete(@Param("key") String key);
}
