package com.liming.rag.identity.application;

import com.liming.rag.identity.application.role.PermissionRegistry;
import com.liming.rag.identity.application.role.IdentityPermissionCatalog;

import static com.liming.rag.identity.api.PermissionCatalog.permission;

import com.liming.rag.identity.api.IdentityPermissions;
import com.liming.rag.identity.api.PermissionCatalog;
import com.liming.rag.identity.api.PermissionCatalog.Group;
import com.liming.rag.identity.api.PermissionCatalog.Section;
import java.util.List;
import java.util.Set;

/**
 * 单元测试用的全系统权限点清单：identity 自己的清单，加上其他上下文声明的一部分权限点（identity 不依赖它们，这里照抄编码，
 * 以各上下文 api 里的声明为准）。真实清单下的权限判定由 rag-app 的 {@code RbacTests} 覆盖。
 */
public final class TestCatalog {

    public static final String CHAT_USE = IdentityPermissions.CHAT_USE;
    public static final String ANSWER_VIEW = "answer:view";
    public static final String STATS_VIEW = "stats:view";
    public static final String MODEL_VIEW = "model:view";

    /** 其他上下文的菜单，位置与各上下文的声明一致。 */
    private static final PermissionCatalog OTHERS = new PermissionCatalog() {
        @Override
        public List<Section> sections() {
            return List.of(
                    new Section(20, new Group("知识库管理", permission("kb:view", "进入页面"), List.of(
                            permission("kb:create", "新建知识库"), permission("kb:grant", "授权")))),
                    new Section(50, new Group("模型管理", permission(MODEL_VIEW, "进入页面"), List.of())),
                    new Section(60, new Group("回答记录", permission(ANSWER_VIEW, "进入页面"), List.of())),
                    new Section(70, new Group("运营概览", permission(STATS_VIEW, "进入页面"), List.of())));
        }

        @Override
        public Set<String> adminDefaults() {
            return Set.of("kb:view", "kb:create", "kb:grant", ANSWER_VIEW, STATS_VIEW);
        }
    };

    public static final PermissionRegistry REGISTRY = new PermissionRegistry(List.of(new IdentityPermissionCatalog(), OTHERS));

    private TestCatalog() {
    }
}
