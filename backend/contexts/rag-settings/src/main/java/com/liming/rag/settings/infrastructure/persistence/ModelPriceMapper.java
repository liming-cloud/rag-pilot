package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** model_price 表：读用通用方法；保存是 upsert，SQL 写在 {@code mapper/settings/ModelPriceMapper.xml}。 */
@Mapper
interface ModelPriceMapper extends BaseMapper<ModelPriceDO> {

    void save(@Param("model") String model, @Param("inputPerMillion") BigDecimal inputPerMillion,
            @Param("outputPerMillion") BigDecimal outputPerMillion, @Param("updatedAt") OffsetDateTime updatedAt);
}
