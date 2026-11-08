package com.liming.rag.data.api;

import static com.liming.rag.identity.api.PermissionCatalog.permission;

import com.liming.rag.identity.api.PermissionCatalog;
import com.liming.rag.identity.api.PermissionCatalog.Group;
import com.liming.rag.identity.api.PermissionCatalog.Section;
import java.util.List;
import java.util.Set;

/** 工具与数据范围授权的权限点（D-056），清单的说明见 {@link PermissionCatalog}。 */
public final class DataPermissions {

    public static final String DATA_VIEW = "data:view";
    public static final String DATA_GRANT = "data:grant";

    public static final List<Section> SECTIONS = List.of(
            new Section(55, new Group("数据范围授权", permission(DATA_VIEW, "进入页面"),
                    List.of(permission(DATA_GRANT, "授权和收回")))));

    /** 内置“管理员”默认拥有数据范围授权的全部权限点；数据范围本身仍要另行授权，管理员不绕过。 */
    public static final Set<String> ADMIN_DEFAULTS = Set.of(DATA_VIEW, DATA_GRANT);

    private DataPermissions() {
    }
}
