package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** model_purpose 表：读用通用方法；保存是 upsert，SQL 写在 {@code mapper/settings/ModelPurposeMapper.xml}。 */
@Mapper
interface ModelPurposeMapper extends BaseMapper<ModelPurposeDO> {

    void save(@Param("purpose") String purpose, @Param("providerId") Long providerId, @Param("model") String model,
            @Param("updatedByName") String updatedByName, @Param("updatedAt") OffsetDateTime updatedAt);
}
