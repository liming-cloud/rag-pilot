package com.liming.rag.identity.application;

import com.liming.rag.identity.api.Permissions;
import java.util.Collection;
import java.util.Set;

/**
 * 防止越权分配（D-038）。超级管理员不受限制，但超级管理员角色谁都不能分配，超级管理员账号别人不能修改。
 * 其他人：分配的角色、给角色的权限点不能超出自己拥有的；只有超级管理员能分配含“角色与权限”的角色；
 * 不能停用、重置密码、改角色的对象是权限不少于自己的账号（包括自己）。
 *
 * @param permissions 操作人自己的权限点
 */
record AssignmentRules(Set<String> permissions, boolean superAdmin) {

    void checkAssignable(Collection<Role> assigned) {
        for (var role : assigned) {
            if (role.superAdmin()) {
                throw escalation("超级管理员角色不能分配");
            }
            if (superAdmin) {
                continue;
            }
            if (role.permissions().stream().anyMatch(code -> code.startsWith("role:"))) {
                throw escalation("只有超级管理员能分配含“角色与权限”的角色：" + role.name());
            }
            if (!permissions.containsAll(role.permissions())) {
                throw escalation("不能分配超出自己权限的角色：" + role.name());
            }
        }
    }

    /** @param target 被操作账号的角色（不看账号状态） */
    void checkManageable(Collection<Role> target) {
        if (target.stream().anyMatch(Role::superAdmin)) {
            throw escalation("超级管理员不能被别人修改");
        }
        if (!superAdmin && AccessService.grantedBy(target).containsAll(permissions)) {
            throw escalation("不能修改权限不少于自己的账号");
        }
    }

    void checkGrantable(Collection<String> granted) {
        if (!Permissions.ALL.containsAll(granted)) {
            throw new IdentityException(IdentityException.Reason.INVALID, "权限点不在清单里");
        }
        if (!superAdmin && !permissions.containsAll(granted)) {
            throw escalation("不能给角色超出自己权限的权限点");
        }
    }

    private static IdentityException escalation(String message) {
        return new IdentityException(IdentityException.Reason.ESCALATION, message);
    }
}
