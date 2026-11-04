package com.liming.rag.settings.infrastructure.persistence;

import java.time.OffsetDateTime;

/** model_purpose 的一行（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class ModelPurposeDO {

    String purpose;
    Long providerId;
    String model;
    String updatedByName;
    OffsetDateTime updatedAt;
}
