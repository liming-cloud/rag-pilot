package com.liming.rag.knowledge.api;

import static com.liming.rag.identity.api.PermissionCatalog.permission;

import com.liming.rag.identity.api.PermissionCatalog;
import com.liming.rag.identity.api.PermissionCatalog.Group;
import com.liming.rag.identity.api.PermissionCatalog.Section;
import java.util.List;
import java.util.Set;

/** 知识库管理的权限点（D-038），清单的说明见 {@link PermissionCatalog}。能管理哪些库另由资源授权决定（D-041）。 */
public final class KnowledgePermissions {

    public static final String KB_VIEW = "kb:view";
    public static final String KB_CREATE = "kb:create";
    public static final String KB_UPDATE = "kb:update";
    public static final String KB_DELETE = "kb:delete";
    public static final String DOC_UPLOAD = "doc:upload";
    public static final String DOC_REPLACE = "doc:replace";
    public static final String DOC_UPDATE = "doc:update";
    public static final String DOC_DELETE = "doc:delete";
    public static final String KB_GRANT = "kb:grant";

    public static final List<Section> SECTIONS = List.of(
            new Section(20, new Group("知识库管理", permission(KB_VIEW, "进入页面"), List.of(
                    permission(KB_CREATE, "新建知识库"), permission(KB_UPDATE, "改名"), permission(KB_DELETE, "删除知识库"),
                    permission(DOC_UPLOAD, "上传文档"), permission(DOC_REPLACE, "上传新版本"),
                    permission(DOC_UPDATE, "修改资料信息"), permission(DOC_DELETE, "删除文档"),
                    permission(KB_GRANT, "授权")))));

    /** 内置“管理员”默认拥有知识库管理的全部权限点。 */
    public static final Set<String> ADMIN_DEFAULTS = Set.of(KB_VIEW, KB_CREATE, KB_UPDATE, KB_DELETE, DOC_UPLOAD,
            DOC_REPLACE, DOC_UPDATE, DOC_DELETE, KB_GRANT);

    private KnowledgePermissions() {
    }
}
