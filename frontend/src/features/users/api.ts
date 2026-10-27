import type { User } from "@/features/auth/api";
import { json } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 本租户全部账号，只有管理员能调用。 */
export async function fetchUsers(): Promise<User[]> {
  return json(await authFetch("/api/users"), "读取账号失败");
}

export type NewUser = { username: string; displayName: string; password: string; role: User["role"] };

export async function createUser(user: NewUser): Promise<User> {
  const response = await authFetch("/api/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(user),
  });
  return json(response, "新建账号失败");
}

/** 停用后该账号已登录的会话立即失效。 */
export async function setUserEnabled(id: number, enabled: boolean): Promise<User> {
  const response = await authFetch(`/api/users/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
  return json(response, enabled ? "启用失败" : "停用失败");
}
