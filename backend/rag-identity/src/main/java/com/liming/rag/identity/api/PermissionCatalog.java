package com.liming.rag.identity.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一个上下文的权限点清单（ROADMAP 第四节、D-038、#136）。权限点命名为 {@code 资源:操作}，按菜单分组：菜单权限决定能不能进页面，
 * 按钮权限决定页面里每个操作能不能做。
 *
 * <p>各上下文在自己的 api 包里声明权限点常量（供 {@code @SaCheckPermission} 使用），并提供一个实现这个接口的 bean；
 * identity 在运行时收集全部清单，用于权限判定、角色页的权限列表和内置角色的默认权限。identity 不反过来依赖下游的上下文。
 * 新增功能时在同一个 PR 里补权限点。
 */
public interface PermissionCatalog {

    /** 这个上下文的菜单分组；{@link Section#position()} 决定在角色页上的先后，全系统不能重复。 */
    List<Section> sections();

    /** 内置“管理员”角色在新租户里默认拥有的权限点。 */
    default Set<String> adminDefaults() {
        return Set.of();
    }

    /** 内置“员工”角色在新租户里默认拥有的权限点。 */
    default Set<String> staffDefaults() {
        return Set.of();
    }

    /** 一个权限点和界面上显示的名字。 */
    record Permission(String code, String label) {
    }

    /** 一个菜单：进入页面的权限和页面里的按钮权限。 */
    record Group(String menu, Permission view, List<Permission> buttons) {

        public Group {
            buttons = List.copyOf(buttons);
        }

        /** 这个菜单的全部权限点：先进入页面，再按钮，按声明顺序。 */
        public List<String> codes() {
            var codes = new ArrayList<String>();
            codes.add(view.code());
            buttons.forEach(button -> codes.add(button.code()));
            return codes;
        }
    }

    /** 菜单分组和它在角色页上的位置（越小越靠前）。 */
    record Section(int position, Group group) {
    }

    static Permission permission(String code, String label) {
        return new Permission(code, label);
    }
}
