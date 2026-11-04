package com.liming.rag.answering.api;

import static com.liming.rag.identity.api.PermissionCatalog.permission;

import com.liming.rag.identity.api.PermissionCatalog;
import com.liming.rag.identity.api.PermissionCatalog.Group;
import com.liming.rag.identity.api.PermissionCatalog.Section;
import java.util.List;
import java.util.Set;

/**
 * 回答记录和运营概览的权限点（D-038），清单的说明见 {@link PermissionCatalog}。员工端的“使用问答”由 identity 声明
 * （{@code IdentityPermissions.CHAT_USE}）。
 */
public final class AnsweringPermissions {

    public static final String ANSWER_VIEW = "answer:view";
    public static final String ANSWER_REVIEW = "answer:review";
    public static final String ANSWER_RECHECK = "answer:recheck";
    public static final String ANSWER_EXPORT = "answer:export";

    public static final String STATS_VIEW = "stats:view";
    public static final String STATS_EXPORT = "stats:export";

    public static final List<Section> SECTIONS = List.of(
            new Section(60, new Group("回答记录", permission(ANSWER_VIEW, "进入页面"), List.of(
                    permission(ANSWER_REVIEW, "标记处理"), permission(ANSWER_RECHECK, "重新回答"),
                    permission(ANSWER_EXPORT, "导出评测题")))),
            new Section(70, new Group("运营概览", permission(STATS_VIEW, "进入页面"), List.of(
                    permission(STATS_EXPORT, "导出")))));

    /** 内置“管理员”默认能查看和处理回答记录、看运营概览。 */
    public static final Set<String> ADMIN_DEFAULTS = Set.of(ANSWER_VIEW, ANSWER_REVIEW, ANSWER_RECHECK,
            ANSWER_EXPORT, STATS_VIEW, STATS_EXPORT);

    private AnsweringPermissions() {
    }
}
