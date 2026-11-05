package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** model_purpose 的一行，主键是用途名。只在 infrastructure 包内读取。 */
@TableName("model_purpose")
@NullUnmarked
final class ModelPurposeDO {

    @TableId(type = IdType.INPUT)
    String purpose;
    Long providerId;
    String model;
    String updatedByName;
    OffsetDateTime updatedAt;

    String getPurpose() {
        return purpose;
    }
}
