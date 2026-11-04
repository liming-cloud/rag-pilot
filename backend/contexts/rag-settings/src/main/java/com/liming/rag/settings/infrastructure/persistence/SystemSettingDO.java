package com.liming.rag.settings.infrastructure.persistence;

import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** system_setting 的一行（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
@NullUnmarked
final class SystemSettingDO {

    String settingKey;
    String value;
    String updatedByName;
    OffsetDateTime updatedAt;
}
