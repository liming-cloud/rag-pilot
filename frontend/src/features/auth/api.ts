import { authFetch, clearToken, saveToken } from "@/lib/auth";

/** 与后端 identity 模块的 UserView 一致。 */
export type User = {
  id: number;
  username: string;
  displayName: string;
  role: "ADMIN" | "STAFF";
  enabled: boolean;
};

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

/** 当前登录的用户。 */
export async function fetchMe(): Promise<User> {
  const response = await authFetch("/api/auth/me");
  if (!response.ok) {
    throw new Error(`读取当前用户失败（${response.status}）`);
  }
  return (await response.json()) as User;
}

/** 退出：通知后端让令牌失效，无论成功与否都清掉本地令牌。 */
export async function logout(): Promise<void> {
  try {
    await authFetch("/api/auth/logout", { method: "POST" });
  } finally {
    clearToken();
  }
}
