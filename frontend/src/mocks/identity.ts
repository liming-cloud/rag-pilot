import { http, HttpResponse } from "msw";
import type { PermissionGroup, Role } from "@/features/roles/api";
import type { Account } from "@/features/users/api";
import type { Me } from "@/lib/permissions";
import { matches, paginate } from "./page";

/**
 * 账号、角色与权限的假接口（#115、D-038）。契约与后端一致：按钮级权限点；超级管理员内置且固定；
 * 只能分配不超过自己权限的角色；连续输错 5 次锁定；管理员重置密码后必须先改密码。
 *
 * 示例账号（密码填 wrong-password 模拟输错）：admin 超级管理员、staff01 店员小王（默认登录）、
 * staff02 店员小李（已锁定）、zhang 店长老张（自定义角色“店长”）、reset01 店员小陈（被重置过密码）。
 */
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
    menu: "模型管理",
    view: { code: "model:view", label: "进入页面" },
    buttons: [
      { code: "model:update", label: "修改配置" },
      { code: "model:test", label: "测试连通" },
    ],
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
    menu: "使用统计",
    view: { code: "stats:view", label: "进入页面" },
    buttons: [{ code: "stats:export", label: "导出" }],
  },
  {
    menu: "系统设置",
    view: { code: "setting:view", label: "进入页面" },
    buttons: [{ code: "setting:update", label: "修改设置" }],
  },
];

const ALL = permissionCatalog.flatMap((group) => [group.view.code, ...group.buttons.map((button) => button.code)]);
const ADMIN_DEFAULT = ALL.filter((code) => /^(chat|kb|doc|user|answer|stats):/.test(code));

type StoredAccount = Omit<Account, "roles"> & { roleIds: number[]; mustChangePassword: boolean; failures: number };

const state = {
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

function permissionsOf(account: StoredAccount): string[] {
  if (account.superAdmin) return ALL;
  return [
    ...new Set(state.roles.filter((role) => account.roleIds.includes(role.id)).flatMap((role) => role.permissions)),
  ];
}

function rolesOf(account: StoredAccount) {
  return state.roles.filter((role) => account.roleIds.includes(role.id)).map(({ id, name }) => ({ id, name }));
}

function view(account: StoredAccount): Account {
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

function current() {
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

const problem = (status: number, code: string, detail: string) =>
  HttpResponse.json({ status, code, detail }, { status, headers: { "Content-Type": "application/problem+json" } });

function forbidden(permission: string) {
  return permissionsOf(current()).includes(permission) ? null : problem(403, "FORBIDDEN", "没有权限执行此操作");
}

/** 只能分配不超过自己权限的角色（D-038）。 */
function escalation(roleIds: number[]) {
  const mine = new Set(permissionsOf(current()));
  const asked = state.roles.filter((role) => roleIds.includes(role.id));
  if (asked.some((role) => role.builtIn === "SUPER_ADMIN" || role.permissions.some((code) => !mine.has(code)))) {
    return problem(403, "IDENTITY_ESCALATION", "不能分配超出自己权限的角色");
  }
  return null;
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

  http.get("/api/users", ({ request }) => {
    const denied = forbidden("user:view");
    if (denied) return denied;
    const url = new URL(request.url);
    const keyword = url.searchParams.get("keyword");
    const roleId = Number(url.searchParams.get("roleId")) || null;
    const status = url.searchParams.get("status");
    const now = new Date();
    const locked = (account: StoredAccount) => account.lockedUntil !== null && new Date(account.lockedUntil) > now;
    const found = state.accounts
      .filter((account) => matches(keyword, account.username, account.displayName))
      .filter((account) => roleId === null || account.roleIds.includes(roleId))
      .filter((account) =>
        status === "locked"
          ? locked(account)
          : status === "enabled"
            ? account.enabled
            : status === "disabled"
              ? !account.enabled
              : true,
      );
    return HttpResponse.json(paginate(found.map(view), url));
  }),
  http.post("/api/users", async ({ request }) => {
    const denied = forbidden("user:create");
    if (denied) return denied;
    const input = (await request.json()) as {
      username: string;
      displayName: string;
      password: string;
      roleIds: number[];
    };
    if (state.accounts.some((account) => account.username === input.username)) {
      return problem(409, "IDENTITY_CONFLICT", `用户名已被使用：${input.username}`);
    }
    const escalated = escalation(input.roleIds);
    if (escalated) return escalated;
    const created: StoredAccount = {
      id: state.nextId++,
      username: input.username,
      displayName: input.displayName,
      enabled: true,
      roleIds: input.roleIds,
      superAdmin: false,
      lockedUntil: null,
      mustChangePassword: false,
      failures: 0,
    };
    state.accounts.push(created);
    return HttpResponse.json(view(created), { status: 201 });
  }),
  http.put("/api/users/:id/enabled", async ({ params, request }) => {
    const denied = forbidden("user:disable");
    if (denied) return denied;
    const account = state.accounts.find((candidate) => candidate.id === Number(params.id));
    if (!account) return problem(404, "IDENTITY_NOT_FOUND", "账号不存在");
    if (account.superAdmin) return problem(403, "IDENTITY_ESCALATION", "超级管理员不能被别人修改");
    const { enabled } = (await request.json()) as { enabled: boolean };
    if (!enabled && account.id === current().id) return problem(400, "IDENTITY_INVALID", "不能停用自己的账号");
    account.enabled = enabled;
    return HttpResponse.json(view(account));
  }),
  http.put("/api/users/:id/roles", async ({ params, request }) => {
    const account = state.accounts.find((candidate) => candidate.id === Number(params.id));
    if (!account) return problem(404, "IDENTITY_NOT_FOUND", "账号不存在");
    if (account.superAdmin) return problem(403, "IDENTITY_ESCALATION", "超级管理员不能被别人修改");
    const { roleIds } = (await request.json()) as { roleIds: number[] };
    const denied = forbidden("user:assign-role") ?? escalation(roleIds);
    if (denied) return denied;
    if (roleIds.length === 0) return problem(400, "IDENTITY_INVALID", "至少要有一个角色");
    account.roleIds = roleIds;
    return HttpResponse.json(view(account));
  }),
  http.post("/api/users/:id/reset-password", async ({ params, request }) => {
    const denied = forbidden("user:reset-password");
    if (denied) return denied;
    const account = state.accounts.find((candidate) => candidate.id === Number(params.id));
    if (!account) return problem(404, "IDENTITY_NOT_FOUND", "账号不存在");
    if (account.superAdmin) return problem(400, "IDENTITY_INVALID", "超级管理员的密码只能自己改");
    const { password } = (await request.json()) as { password: string };
    if (password.length < 8 || password.length > 64) return problem(400, "IDENTITY_INVALID", "密码必须是 8 到 64 位");
    account.mustChangePassword = true;
    return new HttpResponse(null, { status: 204 });
  }),
  http.post("/api/users/:id/unlock", ({ params }) => {
    const denied = forbidden("user:update");
    if (denied) return denied;
    const account = state.accounts.find((candidate) => candidate.id === Number(params.id));
    if (!account) return problem(404, "IDENTITY_NOT_FOUND", "账号不存在");
    account.lockedUntil = null;
    account.failures = 0;
    return new HttpResponse(null, { status: 204 });
  }),
];
