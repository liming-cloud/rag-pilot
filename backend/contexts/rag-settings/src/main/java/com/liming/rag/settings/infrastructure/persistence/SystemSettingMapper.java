package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** system_setting 表：读和删用通用方法；保存是 upsert，SQL 写在 {@code mapper/settings/SystemSettingMapper.xml}。 */
@Mapper
interface SystemSettingMapper extends BaseMapper<SystemSettingDO> {

    void save(@Param("key") String key, @Param("value") String value, @Param("updatedBy") long updatedBy,
            @Param("updatedByName") String updatedByName, @Param("updatedAt") OffsetDateTime updatedAt);
}
