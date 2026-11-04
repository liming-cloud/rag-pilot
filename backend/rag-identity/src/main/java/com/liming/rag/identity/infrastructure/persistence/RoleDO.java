package com.liming.rag.identity.infrastructure.persistence;

/** 角色查询行，rolesOfUsers 另带 user_id（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class RoleDO {

    Long userId;
    Long id;
    Long tenantId;
    String name;
    String builtin;
    String permissions;
}
