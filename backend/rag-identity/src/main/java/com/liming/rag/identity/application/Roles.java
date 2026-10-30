package com.liming.rag.identity.application;

import com.liming.rag.shared.TenantId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 角色存储端口：角色、角色的权限点、账号的角色。 */
public interface Roles {

    Optional<Role> find(TenantId tenantId, long id);

    /** 本租户全部角色，内置角色在前，其余按创建顺序。 */
    List<Role> list(TenantId tenantId);

    Optional<Role> findBuiltIn(TenantId tenantId, Role.BuiltIn builtIn);

    boolean existsByName(TenantId tenantId, String name);

    /** 插入角色和它的权限点，返回带主键的角色。 */
    Role insert(Role role);

    /** 改名字并整体替换权限点。 */
    void update(Role role);

    void delete(long id);

    /** 每个角色有几个账号。没有账号的角色不在结果里。 */
    Map<Long, Long> userCounts(TenantId tenantId);

    /** 账号的角色，按角色 id。 */
    List<Role> rolesOf(long userId);

    /** 多个账号的角色，一次查出；没有角色的账号不在结果里。 */
    Map<Long, List<Role>> rolesOf(Collection<Long> userIds);

    /** 属于这个角色的账号。 */
    List<Long> userIds(long roleId);

    /** 整体替换账号的角色。 */
    void setUserRoles(long userId, Collection<Long> roleIds);
}
