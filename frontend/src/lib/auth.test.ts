import { http, HttpResponse } from "msw";
import { server } from "@/mocks/server";
import { authFetch, getToken, loginPath, returnPath, saveToken, setUnauthorizedHandler } from "./auth";

describe("authFetch", () => {
  afterEach(() => localStorage.clear());

  it("请求带上保存的令牌", async () => {
    saveToken("abc");
    let seen: string | null = null;
    server.use(
      http.get("/api/auth/me", ({ request }) => {
        seen = request.headers.get("Authorization");
        return HttpResponse.json({});
      }),
    );
    await authFetch("/api/auth/me");
    expect(seen).toBe("Bearer abc");
  });

  it("收到 401 时清掉令牌并通知回到登录页", async () => {
    saveToken("expired");
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    server.use(http.get("/api/auth/me", () => new HttpResponse(null, { status: 401 })));

    await authFetch("/api/auth/me");
    expect(getToken()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });
});

describe("登录过期后回到原页面（#145 A6）", () => {
  it("去登录时带上所在的页面；首页和登录页本身不带", () => {
    expect(loginPath("/admin/answers?filter=disliked")).toBe("/login?from=%2Fadmin%2Fanswers%3Ffilter%3Ddisliked");
    expect(loginPath("/")).toBe("/login");
    expect(loginPath("/login?from=%2Fchat")).toBe("/login");
  });

  it("只回到本站的路径：外部地址、协议相对地址、反斜杠地址、登录页都回到默认页", () => {
    expect(returnPath("/chat/901")).toBe("/chat/901");
    expect(returnPath(null)).toBe("/chat");
    expect(returnPath("https://evil.example")).toBe("/chat");
    expect(returnPath("//evil.example")).toBe("/chat");
    expect(returnPath("/\\evil.example")).toBe("/chat");
    expect(returnPath("/login")).toBe("/chat");
  });
});
