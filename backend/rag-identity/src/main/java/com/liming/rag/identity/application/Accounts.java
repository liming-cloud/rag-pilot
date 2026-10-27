package com.liming.rag.identity.application;

import com.liming.rag.common.TenantId;
import java.util.List;
import java.util.Optional;

/** 账号存储端口。 */
public interface Accounts {

    Optional<Account> find(long id);

    Optional<Account> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 本租户全部账号，按创建顺序。 */
    List<Account> list(TenantId tenantId);

    long count();

    void setEnabled(long id, boolean enabled);

    /** 插入新账号，返回带主键的账号。 */
    Account insert(Account account);
}
