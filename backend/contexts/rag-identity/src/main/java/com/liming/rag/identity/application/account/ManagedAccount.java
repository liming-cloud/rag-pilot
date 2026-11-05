package com.liming.rag.identity.application.account;

import com.liming.rag.identity.application.role.Role;
import java.util.List;

/** 管理端看到的一个账号：账号本身、角色、是否超级管理员。 */
public record ManagedAccount(Account account, List<Role> roles, boolean superAdmin) {
}
