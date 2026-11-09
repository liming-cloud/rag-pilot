package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** model_price 的一行，主键是模型名。只在 infrastructure 包内读取。 */
@TableName("model_price")
@NullUnmarked
final class ModelPriceDO {

    @TableId(type = IdType.INPUT)
    String model;
    BigDecimal inputPerMillion;
    BigDecimal outputPerMillion;
    OffsetDateTime updatedAt;

    String getModel() {
        return model;
    }
}
