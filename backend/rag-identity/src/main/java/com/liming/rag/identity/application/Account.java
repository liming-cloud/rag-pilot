package com.liming.rag.identity.application;

import com.liming.rag.shared.TenantId;

/** 一个账号。{@code passwordHash} 是 BCrypt 摘要，不出本模块。 */
public record Account(Long id, TenantId tenantId, String username, String displayName, String passwordHash,
        String role, boolean enabled) {
}
