package com.liming.rag.answering.domain;

import java.time.OffsetDateTime;

/**
 * 回答中 [index] 对应的数据出处（规范第 7 节）：来源系统和数据集、查询条件、数据截至时间、查询时间，与资料引用并列展示。
 * 只记出处，不记查到的数据本身。
 *
 * @param tool       调用的工具名
 * @param conditions 客户系统实际执行的条件，如 {@code store_id=S001，sku=A1001}
 * @param truncated  结果是否因条数上限被截断
 */
public record DataCitation(int index, String tool, String system, String dataset, String conditions,
        OffsetDateTime asOf, OffsetDateTime queriedAt, boolean truncated) {
}
