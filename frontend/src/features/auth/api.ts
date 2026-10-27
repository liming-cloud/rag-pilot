import { saveToken } from "@/lib/auth";

/** 与后端 identity 模块的 UserView 一致。 */
export type User = { id: number; username: string; displayName: string; role: "ADMIN" | "STAFF" };

export class BadCredentialsError extends Error {}

/** 登录成功后保存令牌；用户名或密码错误时抛 BadCredentialsError。 */
export async function login(username: string, password: string): Promise<User> {
  const response = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (response.status === 401) {
    throw new BadCredentialsError("用户名或密码错误");
  }
  if (!response.ok) {
    throw new Error(`登录失败（${response.status}）`);
  }
  const { token, user } = (await response.json()) as { token: string; user: User };
  saveToken(token);
  return user;
}
