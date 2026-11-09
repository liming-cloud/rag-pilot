package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** model_usage_daily 表：范围查询和删除用通用方法；累加是 upsert，聚合在 {@code mapper/settings/ModelUsageDailyMapper.xml}。 */
@Mapper
interface ModelUsageDailyMapper extends BaseMapper<ModelUsageDailyDO> {

    void add(@Param("day") LocalDate day, @Param("purpose") String purpose, @Param("provider") String provider,
            @Param("model") String model, @Param("inputTokens") long inputTokens,
            @Param("outputTokens") long outputTokens);

    List<String> usedModels();
}
