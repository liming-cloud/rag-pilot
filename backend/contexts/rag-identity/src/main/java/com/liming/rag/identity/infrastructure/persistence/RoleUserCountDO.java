package com.liming.rag.identity.infrastructure.persistence;

/** 每个角色的用户数（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class RoleUserCountDO {

    Long roleId;
    Long users;
}
