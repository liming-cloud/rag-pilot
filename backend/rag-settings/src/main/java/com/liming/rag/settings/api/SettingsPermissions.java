package com.liming.rag.settings.api;

import static com.liming.rag.identity.api.PermissionCatalog.permission;

import com.liming.rag.identity.api.PermissionCatalog;
import com.liming.rag.identity.api.PermissionCatalog.Group;
import com.liming.rag.identity.api.PermissionCatalog.Section;
import java.util.List;

/** 模型管理和系统设置的权限点（D-043、D-045），清单的说明见 {@link PermissionCatalog}。内置“管理员”默认没有这些权限点。 */
public final class SettingsPermissions {

    public static final String MODEL_VIEW = "model:view";
    public static final String MODEL_UPDATE = "model:update";
    public static final String MODEL_TEST = "model:test";

    public static final String SETTING_VIEW = "setting:view";
    public static final String SETTING_UPDATE = "setting:update";

    public static final List<Section> SECTIONS = List.of(
            new Section(50, new Group("模型管理", permission(MODEL_VIEW, "进入页面"), List.of(
                    permission(MODEL_UPDATE, "修改配置"), permission(MODEL_TEST, "测试连通")))),
            new Section(80, new Group("系统设置", permission(SETTING_VIEW, "进入页面"), List.of(
                    permission(SETTING_UPDATE, "修改设置")))));

    private SettingsPermissions() {
    }
}
