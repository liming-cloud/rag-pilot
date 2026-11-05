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
/**
 * 登录后回到哪里（#145 A6）：登录过期前所在的页面。只接受本站的路径，防止被人构造的链接带到外部网站；
 * 登录页本身、不像路径的值都回到默认页。
 */
export function returnPath(from: string | null, fallback = "/chat"): string {
  if (!from || !from.startsWith("/") || from.startsWith("//") || from.startsWith("/\\") || from.startsWith("/login")) {
    return fallback;
  }
  return from;
}

/** 去登录页的地址，带上现在所在的页面，登录后回来。 */
export function loginPath(current: string): string {
  return current && current !== "/" && !current.startsWith("/login") ? `/login?from=${encodeURIComponent(current)}` : "/login";
}

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
