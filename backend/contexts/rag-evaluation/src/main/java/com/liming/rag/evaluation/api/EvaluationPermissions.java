package com.liming.rag.evaluation.api;

import static com.liming.rag.identity.api.PermissionCatalog.permission;

import com.liming.rag.identity.api.PermissionCatalog.Group;
import com.liming.rag.identity.api.PermissionCatalog.Section;
import java.util.List;

/** 知识质量的权限点（#224、D-070）：超级管理员全有，内置的管理员、员工默认都不给。 */
public final class EvaluationPermissions {

    public static final String EVAL_VIEW = "eval:view";
    public static final String EVAL_MANAGE = "eval:manage";
    public static final String EVAL_RUN = "eval:run";

    public static final List<Section> SECTIONS = List.of(
            new Section(65, new Group("知识质量", permission(EVAL_VIEW, "进入页面"), List.of(
                    permission(EVAL_MANAGE, "管理题集和标准题"), permission(EVAL_RUN, "试问和运行")))));

    private EvaluationPermissions() {
    }
}
