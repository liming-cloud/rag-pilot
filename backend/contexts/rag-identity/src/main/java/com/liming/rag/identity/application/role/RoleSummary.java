package com.liming.rag.identity.application.role;

/** 一个角色和属于它的账号数。 */
public record RoleSummary(Role role, long userCount) {
}
