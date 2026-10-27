/**
 * 登录令牌。存在 localStorage，关掉页面后仍保持登录，直到后端会话过期（8 小时）。
 * 所有业务接口都经 authFetch 发出：自动带上令牌，收到 401 时清掉令牌并回到登录页。
 */
const TOKEN_KEY = "rag.token";

let onUnauthorized: () => void = () => {};

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function saveToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

/** 由应用入口注册，通常是跳转到登录页。 */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(input, { ...init, headers });
  if (response.status === 401) {
    clearToken();
    onUnauthorized();
  }
  return response;
}
