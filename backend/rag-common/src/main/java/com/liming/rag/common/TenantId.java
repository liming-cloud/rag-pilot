package com.liming.rag.common;

/**
 * 租户标识。
 *
 * <p>试点期只有一个租户，但所有业务表从第一天起都带 {@code tenant_id}，以后扩展多租户时不用迁移数据。
 *
 * @param value 数据库中的租户主键，必须为正数
 */
public record TenantId(long value) {

    /**
     * @throws IllegalArgumentException 当标识不是正数时
     */
    public TenantId {
        if (value <= 0) {
            throw new IllegalArgumentException("租户标识必须为正数");
        }
    }
}
