package com.liming.rag.identity.application.role;

import com.liming.rag.identity.application.IdentityException;

import com.liming.rag.identity.api.IdentityPermissions;
import java.util.Collection;
import java.util.Set;

/**
 * 防止越权分配（D-038）。超级管理员不受限制，但超级管理员角色谁都不能分配，超级管理员账号别人不能修改。
 * 其他人：分配的角色、给角色的权限点不能超出自己拥有的；只有超级管理员能分配含“角色与权限”的角色；
 * 不能停用、重置密码、改角色的对象是权限不少于自己的账号（包括自己）。
 *
 * @param permissions 操作人自己的权限点
 * @param access 算对象账号的权限点、核对权限点是否在清单里
 */
public record AssignmentRules(Set<String> permissions, boolean superAdmin, AccessService access) {

    public void checkAssignable(Collection<Role> assigned) {
        for (var role : assigned) {
            var problem = assignProblem(role);
            if (problem != null) {
                throw escalation(problem);
            }
        }
    }

    /** 不能分配这个角色的原因；能分配时返回 null。批量导入用它逐行报错，不在第一个问题处中断。 */
    public String assignProblem(Role role) {
        if (role.superAdmin()) {
            return "超级管理员角色不能分配";
        }
        if (superAdmin) {
            return null;
        }
        if (role.permissions().stream().anyMatch(code -> code.startsWith(IdentityPermissions.ROLE_PREFIX))) {
            return "只有超级管理员能分配含“角色与权限”的角色：" + role.name();
        }
        if (!permissions.containsAll(role.permissions())) {
            return "不能分配超出自己权限的角色：" + role.name();
        }
        return null;
    }

    /** @param target 被操作账号的角色（不看账号状态） */
    public void checkManageable(Collection<Role> target) {
        if (target.stream().anyMatch(Role::superAdmin)) {
            throw escalation("超级管理员不能被别人修改");
        }
        if (!superAdmin && access.grantedBy(target).containsAll(permissions)) {
            throw escalation("不能修改权限不少于自己的账号");
        }
    }

    void checkGrantable(Collection<String> granted) {
        if (!access.catalog().containsAll(granted)) {
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
