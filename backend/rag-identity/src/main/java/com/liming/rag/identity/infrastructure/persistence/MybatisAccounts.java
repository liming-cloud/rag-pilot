package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.common.TenantId;
import com.liming.rag.identity.application.Account;
import com.liming.rag.identity.application.Accounts;
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
    public List<Account> list(TenantId tenantId) {
        return mapper.selectList(new LambdaQueryWrapper<AppUserDO>()
                        .eq(AppUserDO::getTenantId, tenantId.value())
                        .orderByAsc(AppUserDO::getId))
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
    public Account insert(Account account) {
        var row = new AppUserDO();
        row.setTenantId(account.tenantId().value());
        row.setUsername(account.username());
        row.setDisplayName(account.displayName());
        row.setPasswordHash(account.passwordHash());
        row.setRole(account.role());
        row.setEnabled(account.enabled());
        mapper.insert(row);
        return toAccount(row);
    }

    private static LambdaQueryWrapper<AppUserDO> byUsername(String username) {
        return new LambdaQueryWrapper<AppUserDO>().eq(AppUserDO::getUsername, username);
    }

    private static Account toAccount(AppUserDO row) {
        return new Account(row.getId(), new TenantId(row.getTenantId()), row.getUsername(), row.getDisplayName(),
                row.getPasswordHash(), row.getRole(), Boolean.TRUE.equals(row.getEnabled()));
    }
}
