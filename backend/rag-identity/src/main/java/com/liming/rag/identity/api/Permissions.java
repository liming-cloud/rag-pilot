package com.liming.rag.identity.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限点清单（ROADMAP 第四节、D-038），命名为 {@code 资源:操作}，供 {@code @SaCheckPermission} 使用。按菜单分组：
 * 菜单权限决定能不能进页面，按钮权限决定页面里每个操作能不能做。新增功能时在同一个 PR 里补权限点。
 */
public final class Permissions {

    public static final String CHAT_USE = "chat:use";

    public static final String KB_VIEW = "kb:view";
    public static final String KB_CREATE = "kb:create";
    public static final String KB_UPDATE = "kb:update";
    public static final String KB_DELETE = "kb:delete";
    public static final String DOC_UPLOAD = "doc:upload";
    public static final String DOC_REPLACE = "doc:replace";
    public static final String DOC_DELETE = "doc:delete";
    public static final String KB_GRANT = "kb:grant";

    public static final String USER_VIEW = "user:view";
    public static final String USER_CREATE = "user:create";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_DISABLE = "user:disable";
    public static final String USER_RESET_PASSWORD = "user:reset-password";
    public static final String USER_ASSIGN_ROLE = "user:assign-role";
    public static final String USER_IMPORT = "user:import";

    public static final String ROLE_VIEW = "role:view";
    public static final String ROLE_CREATE = "role:create";
    public static final String ROLE_UPDATE = "role:update";
    public static final String ROLE_DELETE = "role:delete";

    public static final String MODEL_VIEW = "model:view";
    public static final String MODEL_UPDATE = "model:update";
    public static final String MODEL_TEST = "model:test";

    public static final String ANSWER_VIEW = "answer:view";
    public static final String ANSWER_REVIEW = "answer:review";
    public static final String ANSWER_RECHECK = "answer:recheck";
    public static final String ANSWER_EXPORT = "answer:export";

    public static final String STATS_VIEW = "stats:view";
    public static final String STATS_EXPORT = "stats:export";

    public static final String SETTING_VIEW = "setting:view";
    public static final String SETTING_UPDATE = "setting:update";

    /** 一个权限点和界面上显示的名字。 */
    public record Permission(String code, String label) {
    }

    /** 一个菜单：进入页面的权限和页面里的按钮权限。 */
    public record Group(String menu, Permission view, List<Permission> buttons) {
    }

    public static final List<Group> CATALOG = List.of(
            new Group("对话", p(CHAT_USE, "使用问答"), List.of()),
            new Group("知识库管理", p(KB_VIEW, "进入页面"), List.of(p(KB_CREATE, "新建知识库"), p(KB_UPDATE, "改名"),
                    p(KB_DELETE, "删除知识库"), p(DOC_UPLOAD, "上传文档"), p(DOC_REPLACE, "上传新版本"),
                    p(DOC_DELETE, "删除文档"), p(KB_GRANT, "授权"))),
            new Group("用户管理", p(USER_VIEW, "进入页面"), List.of(p(USER_CREATE, "新建账号"),
                    p(USER_UPDATE, "修改与解锁"), p(USER_DISABLE, "停用与启用"), p(USER_RESET_PASSWORD, "重置密码"),
                    p(USER_ASSIGN_ROLE, "分配角色"), p(USER_IMPORT, "批量导入"))),
            new Group("角色与权限", p(ROLE_VIEW, "进入页面"), List.of(p(ROLE_CREATE, "新建角色"),
                    p(ROLE_UPDATE, "修改角色"), p(ROLE_DELETE, "删除角色"))),
            new Group("模型管理", p(MODEL_VIEW, "进入页面"), List.of(p(MODEL_UPDATE, "修改配置"),
                    p(MODEL_TEST, "测试连通"))),
            new Group("回答记录", p(ANSWER_VIEW, "进入页面"), List.of(p(ANSWER_REVIEW, "标记处理"),
                    p(ANSWER_RECHECK, "重新回答"), p(ANSWER_EXPORT, "导出评测题"))),
            new Group("使用统计", p(STATS_VIEW, "进入页面"), List.of(p(STATS_EXPORT, "导出"))),
            new Group("系统设置", p(SETTING_VIEW, "进入页面"), List.of(p(SETTING_UPDATE, "修改设置"))));

    /** 全部权限点，按清单顺序。 */
    public static final Set<String> ALL = all();

    private Permissions() {
    }

    private static Permission p(String code, String label) {
        return new Permission(code, label);
    }

    private static Set<String> all() {
        var codes = new LinkedHashSet<String>();
        for (var group : CATALOG) {
            codes.add(group.view().code());
            group.buttons().forEach(button -> codes.add(button.code()));
        }
        return Collections.unmodifiableSet(codes);
    }
}
