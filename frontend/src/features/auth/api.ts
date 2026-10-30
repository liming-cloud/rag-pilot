import { ApiError } from "@/lib/api";
import { authFetch, clearToken, saveToken } from "@/lib/auth";
import type { Me } from "@/lib/permissions";

export class BadCredentialsError extends Error {}

/**
 * 登录成功后保存令牌。用户名或密码错误时抛 BadCredentialsError；账号被锁定（连续输错 5 次，D-038）等其他失败
 * 抛 ApiError，message 是后端给的说明（如还要等几分钟）。
 */
export async function login(username: string, password: string): Promise<Me> {
  const response = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (response.status === 401) {
    throw new BadCredentialsError("用户名或密码错误");
  }
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { detail?: string; code?: string } | null;
    throw new ApiError(response.status, problem?.detail ?? `登录失败（${response.status}）`, problem?.code);
  }
  const { token, user } = (await response.json()) as { token: string; user: Me };
  saveToken(token);
  return user;
}

/** 改自己的密码，要输入旧密码。管理员重置过密码时，登录后必须先改（D-038）。 */
export async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  const response = await authFetch("/api/auth/password", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ oldPassword, newPassword }),
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { detail?: string; code?: string } | null;
    throw new ApiError(response.status, problem?.detail ?? "修改密码失败", problem?.code);
  }
}

/** 退出：通知后端让令牌失效，无论成功与否都清掉本地令牌。 */
export async function logout(): Promise<void> {
  try {
    await authFetch("/api/auth/logout", { method: "POST" });
  } finally {
    clearToken();
  }
}
