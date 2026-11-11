import type { PermissionGroup } from "@/features/roles/api";

/** 权限点清单，与后端一致（D-038）；契约测试核对它和后端的清单一一对应。 */
export const permissionCatalog: PermissionGroup[] = [
  { menu: "对话", view: { code: "chat:use", label: "使用问答" }, buttons: [] },
  {
    menu: "知识库管理",
    view: { code: "kb:view", label: "进入页面" },
    buttons: [
      { code: "kb:create", label: "新建知识库" },
      { code: "kb:update", label: "改名" },
      { code: "kb:delete", label: "删除知识库" },
      { code: "doc:upload", label: "上传文档" },
      { code: "doc:replace", label: "上传新版本" },
      { code: "doc:update", label: "修改资料信息" },
      { code: "doc:delete", label: "删除文档" },
      { code: "kb:grant", label: "授权" },
    ],
  },
  {
    menu: "用户管理",
    view: { code: "user:view", label: "进入页面" },
    buttons: [
      { code: "user:create", label: "新建账号" },
      { code: "user:update", label: "修改与解锁" },
      { code: "user:disable", label: "停用与启用" },
      { code: "user:reset-password", label: "重置密码" },
      { code: "user:assign-role", label: "分配角色" },
      { code: "user:import", label: "批量导入" },
    ],
  },
  {
    menu: "角色与权限",
    view: { code: "role:view", label: "进入页面" },
    buttons: [
      { code: "role:create", label: "新建角色" },
      { code: "role:update", label: "修改角色" },
      { code: "role:delete", label: "删除角色" },
    ],
  },
  {
    menu: "审计记录",
    view: { code: "audit:view", label: "查看审计记录" },
    buttons: [],
  },
  {
    menu: "模型管理",
    view: { code: "model:view", label: "进入页面" },
    buttons: [
      { code: "model:update", label: "修改配置" },
      { code: "model:test", label: "测试连通" },
    ],
  },
  {
    menu: "数据范围授权",
    view: { code: "data:view", label: "进入页面" },
    buttons: [{ code: "data:grant", label: "授权和收回" }],
  },
  {
    menu: "回答记录",
    view: { code: "answer:view", label: "进入页面" },
    buttons: [
      { code: "answer:review", label: "标记处理" },
      { code: "answer:recheck", label: "重新回答" },
      { code: "answer:export", label: "导出评测题" },
    ],
  },
  {
    menu: "运营概览",
    view: { code: "stats:view", label: "进入页面" },
    buttons: [{ code: "stats:export", label: "导出" }],
  },
  {
    menu: "系统设置",
    view: { code: "setting:view", label: "进入页面" },
    buttons: [{ code: "setting:update", label: "修改设置" }],
  },
];

export const ALL = permissionCatalog.flatMap((group) => [group.view.code, ...group.buttons.map((button) => button.code)]);
export const ADMIN_DEFAULT = ALL.filter((code) => /^(chat|kb|doc|user|answer|stats|audit|data):/.test(code));
