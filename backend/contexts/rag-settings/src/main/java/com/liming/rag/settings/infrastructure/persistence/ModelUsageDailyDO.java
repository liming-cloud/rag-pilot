package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import org.jspecify.annotations.NullUnmarked;

/** model_usage_daily 的一行（联合主键，没有 @TableId）。只在 infrastructure 包内读取。 */
@TableName("model_usage_daily")
@NullUnmarked
final class ModelUsageDailyDO {

    LocalDate day;
    String purpose;
    String provider;
    String model;
    long calls;
    long inputTokens;
    long outputTokens;

    LocalDate getDay() {
        return day;
    }
}
