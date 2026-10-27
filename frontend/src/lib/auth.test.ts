import { http, HttpResponse } from "msw";
import { server } from "@/mocks/server";
import { authFetch, getToken, saveToken, setUnauthorizedHandler } from "./auth";

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
