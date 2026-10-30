import { json } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 内置角色：超级管理员只读；管理员、员工可以改权限但不能删（D-038）。 */
export type BuiltIn = "SUPER_ADMIN" | "ADMIN" | "STAFF";

/** 与后端 /api/roles 一致。 */
export type Role = {
  id: number;
  name: string;
  builtIn: BuiltIn | null;
  permissions: string[];
  /** 属于这个角色的账号数；还有账号的角色不能删。 */
  userCount: number;
};

/** 权限点清单按菜单分组，与 ROADMAP 第四节一致，由后端给出。 */
export type PermissionGroup = {
  menu: string;
  /** 能不能进这个菜单的页面。 */
  view: { code: string; label: string };
  buttons: { code: string; label: string }[];
};

export async function fetchRoles(): Promise<Role[]> {
  return json(await authFetch("/api/roles"), "读取角色失败");
}

export async function fetchPermissionCatalog(): Promise<PermissionGroup[]> {
  return json(await authFetch("/api/permissions"), "读取权限清单失败");
}

export async function createRole(role: { name: string; permissions: string[] }): Promise<Role> {
  const response = await authFetch("/api/roles", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(role),
  });
  return json(response, "新建角色失败");
}

export async function updateRole(id: number, change: { name?: string; permissions?: string[] }): Promise<Role> {
  const response = await authFetch(`/api/roles/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(change),
  });
  return json(response, "保存角色失败");
}

export async function deleteRole(id: number): Promise<void> {
  const response = await authFetch(`/api/roles/${id}`, { method: "DELETE" });
  if (!response.ok) {
    await json(response, "删除角色失败");
  }
}
