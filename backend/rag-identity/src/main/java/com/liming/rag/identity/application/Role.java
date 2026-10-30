package com.liming.rag.identity.application;

import com.liming.rag.shared.TenantId;
import java.util.Set;

/**
 * 一个角色。超级管理员的权限由代码给出（全部权限点），{@code permissions} 只对其他角色有意义。
 *
 * @param builtIn 内置角色；自定义角色为 null
 */
public record Role(Long id, TenantId tenantId, String name, BuiltIn builtIn, Set<String> permissions) {

    public Role {
        permissions = Set.copyOf(permissions);
    }

    /** 内置角色：超级管理员全部权限、不能改；管理员、员工能改权限、不能删（D-038）。 */
    public enum BuiltIn {
        SUPER_ADMIN("超级管理员"),
        ADMIN("管理员"),
        STAFF("员工");

        private final String defaultName;

        BuiltIn(String defaultName) {
            this.defaultName = defaultName;
        }

        public String defaultName() {
            return defaultName;
        }
    }

    public boolean superAdmin() {
        return builtIn == BuiltIn.SUPER_ADMIN;
    }
}
