package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** model_change 的一行，只增不改。只在 infrastructure 包内读取。 */
@TableName("model_change")
@NullUnmarked
final class ModelChangeDO {

    @TableId(type = IdType.AUTO)
    Long id;
    String purpose;
    String beforeProviderName;
    String beforeModel;
    Long afterProviderId;
    String afterProviderName;
    String afterModel;
    Long changedBy;
    String changedByName;
    OffsetDateTime changedAt;
    Long restoredFrom;

    Long getId() {
        return id;
    }

    OffsetDateTime getChangedAt() {
        return changedAt;
    }
}
