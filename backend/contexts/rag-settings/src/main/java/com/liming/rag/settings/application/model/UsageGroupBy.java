package com.liming.rag.settings.application.model;

/** 用量统计的分组维度；名字和接口取值一致。 */
public enum UsageGroupBy {
    /** 不合并：每天、每个用途、服务商、模型各一行。 */
    DETAIL,
    DAY,
    MONTH,
    PURPOSE,
    MODEL
}
