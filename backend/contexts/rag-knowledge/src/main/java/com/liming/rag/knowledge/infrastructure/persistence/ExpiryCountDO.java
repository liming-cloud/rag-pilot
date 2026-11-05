package com.liming.rag.knowledge.infrastructure.persistence;

import org.jspecify.annotations.NullUnmarked;

/** 即将到期和已过期的文档数（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
@NullUnmarked
final class ExpiryCountDO {

    Long expiring;
    Long expired;
}
