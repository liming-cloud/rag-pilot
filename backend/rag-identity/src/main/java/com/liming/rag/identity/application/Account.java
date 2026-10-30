package com.liming.rag.identity.application;

import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;

/**
 * 一个账号。{@code passwordHash} 是 BCrypt 摘要，不出本模块。
 *
 * @param mustChangePassword 管理员重置过密码，登录后必须先改；改之前没有任何权限点（D-038）
 * @param failedAttempts 连续输错密码的次数，登录成功或锁定时清零
 * @param lockedUntil 连续输错被锁到什么时候，没锁为 null
 */
public record Account(Long id, TenantId tenantId, String username, String displayName, String passwordHash,
        boolean enabled, boolean mustChangePassword, int failedAttempts, OffsetDateTime lockedUntil) {

    public boolean lockedAt(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
