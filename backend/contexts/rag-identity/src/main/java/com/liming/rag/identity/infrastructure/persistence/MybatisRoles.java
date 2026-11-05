package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.identity.application.role.Role;
import com.liming.rag.identity.application.role.Roles;
import com.liming.rag.shared.TenantId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** 每张表一个 Mapper：单表读写用 MyBatis-Plus 的通用方法，连表查询和一次插入多行写在 XML 里。 */
@Repository
class MybatisRoles implements Roles {

    private static final String SEPARATOR = "\u001F";

    private final AppRoleMapper mapper;
    private final AppRolePermissionMapper permissions;
    private final AppUserRoleMapper userRoles;

    MybatisRoles(AppRoleMapper mapper, AppRolePermissionMapper permissions, AppUserRoleMapper userRoles) {
        this.mapper = mapper;
        this.permissions = permissions;
        this.userRoles = userRoles;
    }

    @Override
    public Optional<Role> find(TenantId tenantId, long id) {
        return mapper.list(tenantId.value(), id, null).stream().findFirst().map(MybatisRoles::toRole);
    }

    @Override
    public List<Role> list(TenantId tenantId) {
        return mapper.list(tenantId.value(), null, null).stream().map(MybatisRoles::toRole).toList();
    }

    @Override
    public Optional<Role> findBuiltIn(TenantId tenantId, Role.BuiltIn builtIn) {
        return mapper.list(tenantId.value(), null, builtIn.name()).stream().findFirst().map(MybatisRoles::toRole);
    }

    @Override
    public boolean existsByName(TenantId tenantId, String name) {
        return mapper.exists(new LambdaQueryWrapper<AppRoleDO>()
                .eq(AppRoleDO::getTenantId, tenantId.value())
                .eq(AppRoleDO::getName, name));
    }

    @Override
    public Role insert(Role role) {
        var row = new AppRoleDO();
        row.tenantId = role.tenantId().value();
        row.name = role.name();
        row.builtin = role.builtIn() == null ? null : role.builtIn().name();
        mapper.insert(row);
        long id = row.id;
        if (!role.permissions().isEmpty()) {
            permissions.insertAll(id, role.permissions());
        }
        return new Role(id, role.tenantId(), role.name(), role.builtIn(), role.permissions());
    }

    @Override
    public void update(Role role) {
        var row = new AppRoleDO();
        row.id = role.id();
        row.name = role.name();
        mapper.updateById(row);
        permissions.delete(new LambdaQueryWrapper<AppRolePermissionDO>()
                .eq(AppRolePermissionDO::getRoleId, role.id()));
        if (!role.permissions().isEmpty()) {
            permissions.insertAll(role.id(), role.permissions());
        }
    }

    @Override
    public void delete(long id) {
        mapper.deleteById(id);
    }

    @Override
    public Map<Long, Long> userCounts(TenantId tenantId) {
        return mapper.userCounts(tenantId.value()).stream().collect(Collectors.toMap(
                row -> row.roleId, row -> row.users));
    }

    @Override
    public List<Role> rolesOf(long userId) {
        return rolesOf(List.of(userId)).getOrDefault(userId, List.of());
    }

    @Override
    public Map<Long, List<Role>> rolesOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return mapper.rolesOfUsers(userIds).stream().collect(Collectors.groupingBy(
                row -> row.userId, LinkedHashMap::new,
                Collectors.mapping(MybatisRoles::toRole, Collectors.toList())));
    }

    @Override
    public List<Long> userIds(long roleId) {
        return userRoles.selectList(new LambdaQueryWrapper<AppUserRoleDO>()
                        .eq(AppUserRoleDO::getRoleId, roleId).orderByAsc(AppUserRoleDO::getUserId))
                .stream().map(row -> row.userId).toList();
    }

    @Override
    public void setUserRoles(long userId, Collection<Long> roleIds) {
        userRoles.delete(new LambdaQueryWrapper<AppUserRoleDO>().eq(AppUserRoleDO::getUserId, userId));
        if (!roleIds.isEmpty()) {
            userRoles.insertAll(userId, roleIds);
        }
    }

    private static Role toRole(RoleDO row) {
        return new Role(row.id, new TenantId(row.tenantId), row.name,
                row.builtin == null ? null : Role.BuiltIn.valueOf(row.builtin),
                row.permissions == null ? Set.of() : Set.of(row.permissions.split(SEPARATOR)));
    }
}
