import { http, HttpResponse } from "msw";
import type { Role } from "@/features/roles/api";
import type { Account } from "@/features/users/api";
import type { Me } from "@/lib/permissions";
import { ADMIN_DEFAULT, ALL, permissionCatalog } from "./permission-catalog";

/**
 * 账号、角色与权限的假接口（#115、D-038）。契约与后端一致：按钮级权限点；超级管理员内置且固定；
 * 只能分配不超过自己权限的角色；连续输错 5 次锁定；管理员重置密码后必须先改密码。
 *
 * 示例账号（密码填 wrong-password 模拟输错）：admin 超级管理员、staff01 店员小王（默认登录）、
 * staff02 店员小李（已锁定）、zhang 店长老张（自定义角色“店长”）、reset01 店员小陈（被重置过密码）。
 */

export type StoredAccount = Omit<Account, "roles"> & { roleIds: number[]; mustChangePassword: boolean; failures: number };

/** 账号和角色；users.ts 的用户管理接口也读写它。 */
export const state = {
  roles: [] as Omit<Role, "userCount">[],
  accounts: [] as StoredAccount[],
  currentId: 2,
  nextId: 200,
};

function minutesFromNow(minutes: number) {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

export function resetIdentity() {
  state.roles = [
    { id: 1, name: "超级管理员", builtIn: "SUPER_ADMIN", permissions: ALL },
    { id: 2, name: "管理员", builtIn: "ADMIN", permissions: ADMIN_DEFAULT },
    { id: 3, name: "员工", builtIn: "STAFF", permissions: ["chat:use"] },
    { id: 4, name: "店长", builtIn: null, permissions: ["chat:use", "answer:view", "stats:view", "stats:export"] },
  ];
  const account = (
    id: number,
    username: string,
    displayName: string,
    roleIds: number[],
    extra: Partial<StoredAccount> = {},
  ) => ({
    id,
    username,
    displayName,
    enabled: true,
    roleIds,
    superAdmin: false,
    lockedUntil: null,
    mustChangePassword: false,
    failures: 0,
    ...extra,
  });
  state.accounts = [
    account(1, "admin", "管理员", [1], { superAdmin: true }),
    account(2, "staff01", "店员小王", [3]),
    account(3, "staff02", "店员小李", [3], { lockedUntil: minutesFromNow(12), failures: 5 }),
    account(4, "zhang", "店长老张", [4]),
    account(5, "reset01", "店员小陈", [3], { mustChangePassword: true }),
    // 多生成一些店员，原型里能看到分页和筛选。
    ...Array.from({ length: 40 }, (_, index) =>
      account(100 + index, `store${String(index + 1).padStart(2, "0")}`, `门店店员 ${index + 1}`, [3], {
        enabled: index % 9 !== 0,
      }),
    ),
  ];
  state.currentId = 2;
  state.nextId = 200;
}

export function permissionsOf(account: StoredAccount): string[] {
  if (account.superAdmin) return ALL;
  return [
    ...new Set(state.roles.filter((role) => account.roleIds.includes(role.id)).flatMap((role) => role.permissions)),
  ];
}

function rolesOf(account: StoredAccount) {
  return state.roles.filter((role) => account.roleIds.includes(role.id)).map(({ id, name }) => ({ id, name }));
}

export function view(account: StoredAccount): Account {
  const { id, username, displayName, enabled, superAdmin, lockedUntil } = account;
  return { id, username, displayName, enabled, superAdmin, lockedUntil, roles: rolesOf(account) };
}

function me(account: StoredAccount): Me {
  const { id, username, displayName, superAdmin, mustChangePassword } = account;
  return {
    id,
    username,
    displayName,
    superAdmin,
    mustChangePassword,
    roles: rolesOf(account),
    permissions: permissionsOf(account),
  };
}

/** 浏览器里刷新页面后假数据会重置；像真实后端的登录会话一样，记住是谁登录的。 */
const SESSION_KEY = "mock.currentUser";

function remember(id: number) {
  state.currentId = id;
  globalThis.sessionStorage?.setItem(SESSION_KEY, String(id));
}

export function current() {
  const remembered = Number(globalThis.sessionStorage?.getItem(SESSION_KEY));
  return state.accounts.find((account) => account.id === (remembered || state.currentId))!;
}

/** 账号的用户名和显示名，供知识库授权列表显示。 */
export function accountName(id: number): { username: string; displayName: string } {
  const account = state.accounts.find((candidate) => candidate.id === id);
  return { username: account?.username ?? `user${id}`, displayName: account?.displayName ?? `账号 ${id}` };
}

/** 测试用：以示例账号的身份登录（admin、staff01、staff02、zhang、reset01）。 */
export function signInAs(username: string) {
  remember(state.accounts.find((account) => account.username === username)!.id);
}

/** 当前登录账号的 id 和所属角色，供知识库授权判断（直接授权 ∪ 角色授权）。 */
export function currentIdentity(): { id: number; roleIds: number[] } {
  const account = current();
  return { id: account.id, roleIds: account.roleIds };
}

export const problem = (status: number, code: string, detail: string) =>
  HttpResponse.json({ status, code, detail }, { status, headers: { "Content-Type": "application/problem+json" } });

export function forbidden(permission: string) {
  return permissionsOf(current()).includes(permission) ? null : problem(403, "FORBIDDEN", "没有权限执行此操作");
}


function roleView(role: Omit<Role, "userCount">): Role {
  return { ...role, userCount: state.accounts.filter((account) => account.roleIds.includes(role.id)).length };
}

export const identityHandlers = [
  http.post("/api/auth/login", async ({ request }) => {
    const { username, password } = (await request.json()) as { username: string; password: string };
    const account = state.accounts.find((candidate) => candidate.username === username) ?? state.accounts[1]!;
    if (account.lockedUntil && new Date(account.lockedUntil) > new Date()) {
      const minutes = Math.ceil((new Date(account.lockedUntil).getTime() - Date.now()) / 60_000);
      return problem(403, "IDENTITY_LOCKED", `密码连续输错 5 次，账号已锁定，请 ${minutes} 分钟后再试或联系管理员解锁`);
    }
    if (password === "wrong-password") {
      account.failures += 1;
      if (account.failures >= 5) account.lockedUntil = minutesFromNow(15);
      return problem(401, "IDENTITY_BAD_CREDENTIALS", "用户名或密码错误");
    }
    account.failures = 0;
    remember(account.id);
    return HttpResponse.json({ token: "mock-token", user: me(account) });
  }),
  http.get("/api/auth/me", () => HttpResponse.json(me(current()))),
  http.post("/api/auth/logout", () => new HttpResponse(null, { status: 204 })),
  http.post("/api/auth/password", async ({ request }) => {
    const { oldPassword, newPassword } = (await request.json()) as { oldPassword: string; newPassword: string };
    if (oldPassword === "wrong-password") return problem(400, "IDENTITY_INVALID", "当前密码不对");
    if (newPassword.length < 8 || newPassword.length > 64)
      return problem(400, "IDENTITY_INVALID", "密码必须是 8 到 64 位");
    if (newPassword === current().username) return problem(400, "IDENTITY_INVALID", "密码不能与用户名相同");
    current().mustChangePassword = false;
    return new HttpResponse(null, { status: 204 });
  }),

  http.get("/api/permissions", () => forbidden("role:view") ?? HttpResponse.json(permissionCatalog)),
  http.get("/api/roles", () => {
    // 角色列表在角色页、用户页（分配角色）、知识库授权里都要用。
    const allowed = permissionsOf(current()).some((code) => ["role:view", "user:view", "kb:grant"].includes(code));
    return allowed ? HttpResponse.json(state.roles.map(roleView)) : problem(403, "FORBIDDEN", "没有权限执行此操作");
  }),
  http.post("/api/roles", async ({ request }) => {
    const denied = forbidden("role:create");
    if (denied) return denied;
    const { name, permissions } = (await request.json()) as { name: string; permissions: string[] };
    if (state.roles.some((role) => role.name === name))
      return problem(409, "IDENTITY_CONFLICT", `已有同名角色：${name}`);
    const created = { id: state.nextId++, name, builtIn: null, permissions };
    state.roles.push(created);
    return HttpResponse.json(roleView(created), { status: 201 });
  }),
  http.patch("/api/roles/:id", async ({ params, request }) => {
    const denied = forbidden("role:update");
    if (denied) return denied;
    const role = state.roles.find((candidate) => candidate.id === Number(params.id));
    if (!role) return problem(404, "IDENTITY_NOT_FOUND", "角色不存在");
    if (role.builtIn === "SUPER_ADMIN") return problem(400, "IDENTITY_INVALID", "超级管理员的权限不能修改");
    const change = (await request.json()) as { name?: string; permissions?: string[] };
    if (change.name !== undefined && role.builtIn === null) role.name = change.name;
    if (change.permissions !== undefined) role.permissions = change.permissions;
    return HttpResponse.json(roleView(role));
  }),
  http.delete("/api/roles/:id", ({ params }) => {
    const denied = forbidden("role:delete");
    if (denied) return denied;
    const role = state.roles.find((candidate) => candidate.id === Number(params.id));
    if (!role) return problem(404, "IDENTITY_NOT_FOUND", "角色不存在");
    if (role.builtIn) return problem(400, "IDENTITY_INVALID", "内置角色不能删除");
    if (roleView(role).userCount > 0) return problem(409, "IDENTITY_CONFLICT", "还有账号属于这个角色，先移走才能删除");
    state.roles = state.roles.filter((candidate) => candidate !== role);
    return new HttpResponse(null, { status: 204 });
  }),
];
