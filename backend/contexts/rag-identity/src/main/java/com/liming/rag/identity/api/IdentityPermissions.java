package com.liming.rag.identity.api;

import static com.liming.rag.identity.api.PermissionCatalog.permission;

import com.liming.rag.identity.api.PermissionCatalog.Group;
import com.liming.rag.identity.api.PermissionCatalog.Section;
import java.util.List;
import java.util.Set;

/**
 * 账号和角色管理的权限点（D-038），以及员工端的入口权限“使用问答”，清单的说明见 {@link PermissionCatalog}。
 * “使用问答”放在这里，是因为问答页、选库、问答页的文案分属 answering、knowledge、settings，identity 是它们共同的上游。
 */
public final class IdentityPermissions {

    public static final String CHAT_USE = "chat:use";

    public static final String USER_VIEW = "user:view";
    public static final String USER_CREATE = "user:create";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_DISABLE = "user:disable";
    public static final String USER_RESET_PASSWORD = "user:reset-password";
    public static final String USER_ASSIGN_ROLE = "user:assign-role";
    public static final String USER_IMPORT = "user:import";

    /** 角色与权限的权限点都以它开头：只有超级管理员能分配含这些权限点的角色。 */
    public static final String ROLE_PREFIX = "role:";
    public static final String ROLE_VIEW = "role:view";
    public static final String ROLE_CREATE = "role:create";
    public static final String ROLE_UPDATE = "role:update";
    public static final String ROLE_DELETE = "role:delete";

    /** 查看管理操作的审计记录（#145）。 */
    public static final String AUDIT_VIEW = "audit:view";

    public static final List<Section> SECTIONS = List.of(
            new Section(10, new Group("对话", permission(CHAT_USE, "使用问答"), List.of())),
            new Section(30, new Group("用户管理", permission(USER_VIEW, "进入页面"), List.of(
                    permission(USER_CREATE, "新建账号"), permission(USER_UPDATE, "修改与解锁"),
                    permission(USER_DISABLE, "停用与启用"), permission(USER_RESET_PASSWORD, "重置密码"),
                    permission(USER_ASSIGN_ROLE, "分配角色"), permission(USER_IMPORT, "批量导入")))),
            new Section(40, new Group("角色与权限", permission(ROLE_VIEW, "进入页面"), List.of(
                    permission(ROLE_CREATE, "新建角色"), permission(ROLE_UPDATE, "修改角色"),
                    permission(ROLE_DELETE, "删除角色")))),
            new Section(45, new Group("审计记录", permission(AUDIT_VIEW, "查看审计记录"), List.of())));

    /** 内置“管理员”默认能问答、管理账号、查看审计记录，不能改角色与权限。 */
    public static final Set<String> ADMIN_DEFAULTS = Set.of(CHAT_USE, USER_VIEW, USER_CREATE, USER_UPDATE, USER_DISABLE,
            USER_RESET_PASSWORD, USER_ASSIGN_ROLE, USER_IMPORT, AUDIT_VIEW);

    /** 内置“员工”默认只能问答。 */
    public static final Set<String> STAFF_DEFAULTS = Set.of(CHAT_USE);

    private IdentityPermissions() {
    }
}
