import { json, type Page, query } from "@/lib/api";
import { authFetch } from "@/lib/auth";
import type { RoleRef } from "@/lib/permissions";

/** 与后端 /api/users 一致。 */
export type Account = {
  id: number;
  username: string;
  displayName: string;
  enabled: boolean;
  roles: RoleRef[];
  /** 超级管理员：不能被停用、删除、去掉角色，也不能被别人重置密码（D-038）。 */
  superAdmin: boolean;
  /** 连续输错密码被锁定，到期自动解锁，管理员也可以手动解锁。 */
  lockedUntil: string | null;
};

/** 账号列表的筛选：关键词匹配用户名和显示名；状态里的“已锁定”指连续输错被锁、还没到期的账号。 */
export type UserFilter = {
  keyword?: string;
  roleId?: number | null;
  status?: "enabled" | "disabled" | "locked" | null;
  page?: number;
  size?: number;
};

/** 按筛选条件分页读取本租户的账号（D-039）。 */
export async function fetchUsers(filter: UserFilter = {}): Promise<Page<Account>> {
  return json(await authFetch(`/api/users${query(filter)}`), "读取账号失败");
}

export type NewAccount = { username: string; displayName: string; password: string; roleIds: number[] };

export async function createUser(account: NewAccount): Promise<Account> {
  const response = await authFetch("/api/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(account),
  });
  return json(response, "新建账号失败");
}

/** 启用或停用。停用后该账号已登录的会话立即失效（user:disable）。 */
export async function setUserEnabled(id: number, enabled: boolean): Promise<Account> {
  const response = await authFetch(`/api/users/${id}/enabled`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
  return json(response, "保存账号失败");
}

/** 整体替换账号的角色，至少一个；只能分配不超过自己权限的角色（user:assign-role，D-038）。 */
export async function assignRoles(id: number, roleIds: number[]): Promise<Account> {
  const response = await authFetch(`/api/users/${id}/roles`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ roleIds }),
  });
  return json(response, "保存角色失败");
}

/** 重置为管理员给的临时密码；员工下次登录必须先改密码。 */
export async function resetPassword(id: number, password: string): Promise<void> {
  const response = await authFetch(`/api/users/${id}/reset-password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ password }),
  });
  if (!response.ok) await json(response, "重置密码失败");
}

export async function unlockUser(id: number): Promise<void> {
  const response = await authFetch(`/api/users/${id}/unlock`, { method: "POST" });
  if (!response.ok) await json(response, "解锁失败");
}

/** 导入文件里的一行；line 是文件里的行号（表头是第 1 行），用于逐行报错。 */
export type ImportRow = { line: number; username: string; displayName: string; roleNames: string[] };
export type ImportProblem = { line: number; message: string };

/**
 * 预检（user:import）：逐行检查用户名格式和重复、是否已存在、角色是否存在、是否超出自己可分配的范围，
 * 返回全部问题；不写入任何数据。
 */
export async function checkImport(rows: ImportRow[]): Promise<ImportProblem[]> {
  const response = await authFetch("/api/users/import/check", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rows }),
  });
  return (await json<{ problems: ImportProblem[] }>(response, "预检失败")).problems;
}

/**
 * 导入（user:import）：后端重新预检，有任何一行有问题就一条都不写入；全部通过时用统一初始密码建号，
 * 所有导入的账号首次登录必须先改密码（D-038）。
 */
export async function importUsers(rows: ImportRow[], password: string): Promise<{ imported: number }> {
  const response = await authFetch("/api/users/import", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rows, password }),
  });
  return json(response, "导入失败");
}

/** 账号相关的查询键；all 是前缀。 */
export const userKeys = {
  all: ["users"],
  list: (filter: UserFilter) => ["users", filter] as const,
} as const;
