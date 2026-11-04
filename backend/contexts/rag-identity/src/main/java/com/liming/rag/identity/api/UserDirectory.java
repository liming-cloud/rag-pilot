package com.liming.rag.identity.api;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 账号与角色查询，供其他模块授权、检索过滤和显示名字时使用。都只查本租户。 */
public interface UserDirectory {

    /** 账号存在、属于该租户且未停用时返回 true。 */
    boolean exists(TenantId tenantId, UserId userId);

    /** 账号所属的角色；账号不存在或已停用时为空，检索时就只剩直接授权（也是空）。 */
    List<Long> roleIds(TenantId tenantId, UserId userId);

    /** 是不是超级管理员。超级管理员在管理端能看到全部知识库，但检索时同样按授权过滤（ROADMAP 第四节）。 */
    boolean isSuperAdmin(TenantId tenantId, UserId userId);

    /** 角色存在且属于该租户。 */
    boolean roleExists(TenantId tenantId, long roleId);

    /** 按 id 取账号的用户名和显示名，不存在的 id 不返回。 */
    Map<Long, UserSummary> summaries(TenantId tenantId, Collection<Long> userIds);

    /** 账号的用户名和显示名。 */
    record UserSummary(long id, String username, String displayName) {
    }
}
