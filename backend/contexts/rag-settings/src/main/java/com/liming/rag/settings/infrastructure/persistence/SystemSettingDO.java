package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** system_setting 的一行，主键是设置项的名字。只在 infrastructure 包内读取。 */
@TableName("system_setting")
@NullUnmarked
final class SystemSettingDO {

    @TableId(type = IdType.INPUT)
    String settingKey;
    String value;
    Long updatedBy;
    String updatedByName;
    OffsetDateTime updatedAt;
}
