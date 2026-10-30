package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.identity.application.Account;
import com.liming.rag.identity.application.AccountQuery;
import com.liming.rag.identity.application.Accounts;
import com.liming.rag.identity.application.Paged;
import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MybatisAccounts implements Accounts {

    private final AppUserMapper mapper;

    MybatisAccounts(AppUserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Account> find(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MybatisAccounts::toAccount);
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return Optional.ofNullable(mapper.selectOne(byUsername(username))).map(MybatisAccounts::toAccount);
    }

    @Override
    public boolean existsByUsername(String username) {
        return mapper.exists(byUsername(username));
    }

    @Override
    public Paged<Account> page(TenantId tenantId, AccountQuery query) {
        var status = query.status() == null ? null : query.status().name();
        var page = query.page();
        var rows = mapper.page(tenantId.value(), query.keyword(), query.roleId(), status, query.now(), page.offset(),
                page.size());
        long total = mapper.countPage(tenantId.value(), query.keyword(), query.roleId(), status, query.now());
        return new Paged<>(rows.stream().map(MybatisAccounts::toAccount).toList(), total, page.page(), page.size());
    }

    @Override
    public List<Account> findAll(TenantId tenantId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<AppUserDO>()
                        .eq(AppUserDO::getTenantId, tenantId.value())
                        .in(AppUserDO::getId, ids))
                .stream().map(MybatisAccounts::toAccount).toList();
    }

    @Override
    public long count() {
        return mapper.selectCount(null);
    }

    @Override
    public void setEnabled(long id, boolean enabled) {
        var row = new AppUserDO();
        row.setId(id);
        row.setEnabled(enabled);
        mapper.updateById(row);
    }

    @Override
    public void setPassword(long id, String passwordHash, boolean mustChangePassword) {
        mapper.updatePassword(id, passwordHash, mustChangePassword);
    }

    @Override
    public void recordFailedLogin(long id, int failedAttempts, OffsetDateTime lockedUntil) {
        mapper.updateFailedLogins(id, failedAttempts, lockedUntil);
    }

    @Override
    public void clearFailedLogins(long id) {
        mapper.updateFailedLogins(id, 0, null);
    }

    @Override
    public Account insert(Account account) {
        var row = new AppUserDO();
        row.setTenantId(account.tenantId().value());
        row.setUsername(account.username());
        row.setDisplayName(account.displayName());
        row.setPasswordHash(account.passwordHash());
        row.setEnabled(account.enabled());
        row.setMustChangePassword(account.mustChangePassword());
        row.setFailedAttempts(account.failedAttempts());
        mapper.insert(row);
        return toAccount(row);
    }

    private static LambdaQueryWrapper<AppUserDO> byUsername(String username) {
        return new LambdaQueryWrapper<AppUserDO>().eq(AppUserDO::getUsername, username);
    }

    private static Account toAccount(AppUserDO row) {
        return new Account(row.getId(), new TenantId(row.getTenantId()), row.getUsername(), row.getDisplayName(),
                row.getPasswordHash(), Boolean.TRUE.equals(row.getEnabled()),
                Boolean.TRUE.equals(row.getMustChangePassword()),
                row.getFailedAttempts() == null ? 0 : row.getFailedAttempts(), row.getLockedUntil());
    }
}
