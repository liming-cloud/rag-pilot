import { http, HttpResponse } from "msw";
import type { ImportProblem, ImportRow } from "@/features/users/api";
import { current, forbidden, permissionsOf, problem, state, type StoredAccount, view } from "./identity";
import { matches, paginate } from "./page";

/** 用户管理的假接口（D-038、D-039、#124）；账号数据在 identity.ts。 */
/** 只能分配不超过自己权限的角色（D-038）。 */
function escalation(roleIds: number[]) {
  const mine = new Set(permissionsOf(current()));
  const asked = state.roles.filter((role) => roleIds.includes(role.id));
  if (asked.some((role) => role.builtIn === "SUPER_ADMIN" || role.permissions.some((code) => !mine.has(code)))) {
    return problem(403, "IDENTITY_ESCALATION", "不能分配超出自己权限的角色");
  }
  return null;
}

/** 与后端一致的逐行检查：用户名格式、文件内重复、已存在，显示名，角色存在且不超出自己的权限。 */
function importProblems(rows: ImportRow[]): ImportProblem[] {
  const problems: ImportProblem[] = [];
  const mine = new Set(permissionsOf(current()));
  const seen = new Map<string, number>();
  for (const row of rows) {
    const add = (message: string) => problems.push({ line: row.line, message });
    if (!/^[A-Za-z0-9_.-]{3,50}$/.test(row.username)) add("用户名必须是 3 到 50 个字母、数字或 _ . -");
    else if (seen.has(row.username)) add(`与第 ${seen.get(row.username)} 行的用户名重复`);
    else if (state.accounts.some((account) => account.username === row.username)) add("用户名已存在");
    seen.set(row.username, seen.get(row.username) ?? row.line);
    if (row.displayName.length === 0 || row.displayName.length > 50) add("显示名必须是 1 到 50 个字符");
    if (row.roleNames.length === 0) add("至少要有一个角色");
    for (const name of row.roleNames) {
      const role = state.roles.find((candidate) => candidate.name === name);
      if (!role) add(`没有叫“${name}”的角色`);
      else if (role.builtIn === "SUPER_ADMIN") add("超级管理员角色不能分配");
      else if (role.permissions.some((code) => !mine.has(code))) add(`不能分配超出自己权限的角色：${name}`);
    }
  }
  return problems;
}

export const userHandlers = [
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
  // 批量导入（#124）：预检逐行报错、不写入；导入时重新预检，有一行有问题就一条都不写。
  http.post("/api/users/import/check", async ({ request }) => {
    const denied = forbidden("user:import");
    if (denied) return denied;
    const { rows } = (await request.json()) as { rows: ImportRow[] };
    return HttpResponse.json({ problems: importProblems(rows) });
  }),
  http.post("/api/users/import", async ({ request }) => {
    const denied = forbidden("user:import");
    if (denied) return denied;
    const { rows, password } = (await request.json()) as { rows: ImportRow[]; password: string };
    if (password.length < 8 || password.length > 64) {
      return problem(400, "IDENTITY_INVALID", "初始密码必须是 8 到 64 个字符");
    }
    const lines = new Set(importProblems(rows).map((found) => found.line)).size;
    if (lines > 0) return problem(400, "IDENTITY_INVALID", `导入文件有 ${lines} 行有问题，请重新检查后再导入`);
    for (const row of rows) {
      state.accounts.push({
        id: state.nextId++,
        username: row.username,
        displayName: row.displayName,
        enabled: true,
        roleIds: state.roles.filter((role) => row.roleNames.includes(role.name)).map((role) => role.id),
        superAdmin: false,
        lockedUntil: null,
        mustChangePassword: true,
        failures: 0,
      });
    }
    return HttpResponse.json({ imported: rows.length });
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
