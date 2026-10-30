package com.liming.rag.identity.application;

import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 账号存储端口。 */
public interface Accounts {

    Optional<Account> find(long id);

    Optional<Account> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 按筛选条件分页，按创建顺序。 */
    Paged<Account> page(TenantId tenantId, AccountQuery query);

    /** 本租户里这些 id 的账号，不存在的不返回。 */
    List<Account> findAll(TenantId tenantId, Collection<Long> ids);

    long count();

    void setEnabled(long id, boolean enabled);

    /** 换密码，同时清掉登录失败计数和锁定。 */
    void setPassword(long id, String passwordHash, boolean mustChangePassword);

    /** 记一次密码错误；{@code lockedUntil} 不为 null 时锁定并把计数清零。 */
    void recordFailedLogin(long id, int failedAttempts, OffsetDateTime lockedUntil);

    /** 登录成功或管理员解锁：清掉失败计数和锁定。 */
    void clearFailedLogins(long id);

    /** 插入新账号，返回带主键的账号。 */
    Account insert(Account account);
}
