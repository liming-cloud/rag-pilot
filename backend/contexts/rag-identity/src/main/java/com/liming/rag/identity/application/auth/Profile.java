package com.liming.rag.identity.application.auth;

import com.liming.rag.identity.application.account.Account;
import com.liming.rag.identity.application.role.Role;
import java.util.List;

/** 当前登录的账号、角色和权限点，供前端显示菜单和按钮。 */
public record Profile(Account account, List<Role> roles, List<String> permissions, boolean superAdmin) {
}
