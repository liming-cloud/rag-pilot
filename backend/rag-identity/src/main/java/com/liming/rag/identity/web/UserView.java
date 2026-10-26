package com.liming.rag.identity.web;

import com.liming.rag.identity.application.Account;

/** 对外展示的账号信息，不含密码摘要。 */
record UserView(long id, String username, String displayName, String role) {

    static UserView of(Account account) {
        return new UserView(account.id(), account.username(), account.displayName(), account.role());
    }
}
