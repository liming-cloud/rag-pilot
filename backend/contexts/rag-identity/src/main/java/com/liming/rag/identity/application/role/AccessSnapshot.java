package com.liming.rag.identity.application.role;

import java.util.List;

/**
 * 一个账号判定权限要用的全部信息，经 {@link AccessSnapshotCache} 缓存。
 *
 * @param permissions 现在能用的权限点（已按账号状态算好）
 */
public record AccessSnapshot(long accountId, long tenantId, boolean enabled, List<Long> roleIds, boolean superAdmin,
        List<String> permissions) {
}
