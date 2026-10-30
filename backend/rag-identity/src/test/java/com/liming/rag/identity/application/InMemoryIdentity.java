package com.liming.rag.identity.application;

import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** 账号和角色存储的内存实现，只用于单元测试。 */
final class InMemoryIdentity implements Accounts, Roles {

    final List<Account> accounts = new ArrayList<>();
    final Map<Long, Role> roles = new LinkedHashMap<>();
    final Map<Long, List<Long>> userRoles = new HashMap<>();
    private long nextRoleId = 1;

    @Override
    public Optional<Account> find(long id) {
        return accounts.stream().filter(account -> account.id() == id).findFirst();
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return accounts.stream().filter(account -> account.username().equals(username)).findFirst();
    }

    @Override
    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    @Override
    public Paged<Account> page(TenantId tenantId, AccountQuery query) {
        var found = accounts.stream().filter(account -> account.tenantId().equals(tenantId)).toList();
        return new Paged<>(found, found.size(), query.page().page(), query.page().size());
    }

    @Override
    public List<Account> findAll(TenantId tenantId, Collection<Long> ids) {
        return accounts.stream().filter(account -> account.tenantId().equals(tenantId) && ids.contains(account.id()))
                .toList();
    }

    @Override
    public long count() {
        return accounts.size();
    }

    @Override
    public void setEnabled(long id, boolean enabled) {
        update(id, a -> new Account(a.id(), a.tenantId(), a.username(), a.displayName(), a.passwordHash(), enabled,
                a.mustChangePassword(), a.failedAttempts(), a.lockedUntil()));
    }

    @Override
    public void setPassword(long id, String passwordHash, boolean mustChangePassword) {
        update(id, a -> new Account(a.id(), a.tenantId(), a.username(), a.displayName(), passwordHash, a.enabled(),
                mustChangePassword, 0, null));
    }

    @Override
    public void recordFailedLogin(long id, int failedAttempts, OffsetDateTime lockedUntil) {
        update(id, a -> new Account(a.id(), a.tenantId(), a.username(), a.displayName(), a.passwordHash(),
                a.enabled(), a.mustChangePassword(), failedAttempts, lockedUntil));
    }

    @Override
    public void clearFailedLogins(long id) {
        recordFailedLogin(id, 0, null);
    }

    @Override
    public Account insert(Account account) {
        var stored = new Account((long) accounts.size() + 1, account.tenantId(), account.username(),
                account.displayName(), account.passwordHash(), account.enabled(), account.mustChangePassword(),
                account.failedAttempts(), account.lockedUntil());
        accounts.add(stored);
        return stored;
    }

    private void update(long id, UnaryOperator<Account> change) {
        var account = find(id).orElseThrow();
        accounts.set(accounts.indexOf(account), change.apply(account));
    }

    @Override
    public Optional<Role> find(TenantId tenantId, long id) {
        return Optional.ofNullable(roles.get(id)).filter(role -> role.tenantId().equals(tenantId));
    }

    @Override
    public List<Role> list(TenantId tenantId) {
        return roles.values().stream().filter(role -> role.tenantId().equals(tenantId)).toList();
    }

    @Override
    public Optional<Role> findBuiltIn(TenantId tenantId, Role.BuiltIn builtIn) {
        return list(tenantId).stream().filter(role -> role.builtIn() == builtIn).findFirst();
    }

    @Override
    public boolean existsByName(TenantId tenantId, String name) {
        return list(tenantId).stream().anyMatch(role -> role.name().equals(name));
    }

    @Override
    public Role insert(Role role) {
        var stored = new Role(nextRoleId++, role.tenantId(), role.name(), role.builtIn(), role.permissions());
        roles.put(stored.id(), stored);
        return stored;
    }

    @Override
    public void update(Role role) {
        roles.put(role.id(), role);
    }

    @Override
    public void delete(long id) {
        roles.remove(id);
    }

    @Override
    public Map<Long, Long> userCounts(TenantId tenantId) {
        var counts = new HashMap<Long, Long>();
        userRoles.values().forEach(ids -> ids.forEach(id -> counts.merge(id, 1L, Long::sum)));
        return counts;
    }

    @Override
    public List<Role> rolesOf(long userId) {
        return userRoles.getOrDefault(userId, List.of()).stream().map(roles::get).toList();
    }

    @Override
    public Map<Long, List<Role>> rolesOf(Collection<Long> userIds) {
        var result = new HashMap<Long, List<Role>>();
        userIds.forEach(id -> result.put(id, rolesOf(id)));
        return result;
    }

    @Override
    public List<Long> userIds(long roleId) {
        return userRoles.entrySet().stream().filter(entry -> entry.getValue().contains(roleId))
                .map(Map.Entry::getKey).sorted().toList();
    }

    @Override
    public void setUserRoles(long userId, Collection<Long> roleIds) {
        userRoles.put(userId, List.copyOf(roleIds));
    }
}
