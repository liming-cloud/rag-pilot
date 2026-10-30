package com.liming.rag.identity.infrastructure.persistence;

import com.liming.rag.identity.application.Role;
import com.liming.rag.identity.application.Roles;
import com.liming.rag.shared.TenantId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
class MybatisRoles implements Roles {

    private static final String SEPARATOR = "\u001F";

    private final AppRoleMapper mapper;

    MybatisRoles(AppRoleMapper mapper) {
        this.mapper = mapper;
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
        return mapper.existsByName(tenantId.value(), name);
    }

    @Override
    public Role insert(Role role) {
        long id = mapper.insert(role.tenantId().value(), role.name(),
                role.builtIn() == null ? null : role.builtIn().name());
        if (!role.permissions().isEmpty()) {
            mapper.insertPermissions(id, role.permissions());
        }
        return new Role(id, role.tenantId(), role.name(), role.builtIn(), role.permissions());
    }

    @Override
    public void update(Role role) {
        mapper.rename(role.id(), role.name());
        mapper.deletePermissions(role.id());
        if (!role.permissions().isEmpty()) {
            mapper.insertPermissions(role.id(), role.permissions());
        }
    }

    @Override
    public void delete(long id) {
        mapper.delete(id);
    }

    @Override
    public Map<Long, Long> userCounts(TenantId tenantId) {
        return mapper.userCounts(tenantId.value()).stream().collect(Collectors.toMap(
                row -> ((Number) row.get("role_id")).longValue(), row -> ((Number) row.get("users")).longValue()));
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
                row -> ((Number) row.get("user_id")).longValue(), LinkedHashMap::new,
                Collectors.mapping(MybatisRoles::toRole, Collectors.toList())));
    }

    @Override
    public List<Long> userIds(long roleId) {
        return mapper.userIds(roleId);
    }

    @Override
    public void setUserRoles(long userId, Collection<Long> roleIds) {
        mapper.deleteUserRoles(userId);
        if (!roleIds.isEmpty()) {
            mapper.insertUserRoles(userId, roleIds);
        }
    }

    private static Role toRole(Map<String, Object> row) {
        var builtin = (String) row.get("builtin");
        var permissions = (String) row.get("permissions");
        return new Role(((Number) row.get("id")).longValue(), new TenantId(((Number) row.get("tenant_id")).longValue()),
                (String) row.get("name"), builtin == null ? null : Role.BuiltIn.valueOf(builtin),
                permissions == null ? Set.of() : Set.of(permissions.split(SEPARATOR)));
    }
}
