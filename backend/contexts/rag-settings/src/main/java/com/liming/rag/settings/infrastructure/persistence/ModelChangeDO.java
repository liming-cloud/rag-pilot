package com.liming.rag.settings.infrastructure.persistence;

import java.time.OffsetDateTime;

/** model_change 的一行（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class ModelChangeDO {

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
}
