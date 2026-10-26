package com.liming.rag.identity.application;

import java.util.Optional;

/** 账号存储端口。 */
public interface Accounts {

    Optional<Account> find(long id);

    Optional<Account> findByUsername(String username);

    boolean existsByUsername(String username);

    long count();

    /** 插入新账号，返回带主键的账号。 */
    Account insert(Account account);
}
