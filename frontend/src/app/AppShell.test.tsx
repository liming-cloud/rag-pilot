import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { getToken, saveToken } from "@/lib/auth";
import { server } from "@/mocks/server";
import { routes } from "./router";

const ADMIN = { id: 1, username: "admin", displayName: "管理员", role: "ADMIN", enabled: true };

function renderAt(path: string) {
  saveToken("mock-token");
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe("页面外壳", () => {
  afterEach(() => localStorage.clear());

  it("管理员看到全部入口和自己的名字", async () => {
    server.use(http.get("/api/auth/me", () => HttpResponse.json(ADMIN)));
    renderAt("/chat");

    const nav = await screen.findByRole("navigation", { name: "主导航" });
    for (const name of ["问答", "知识库", "账号", "回答记录"]) {
      expect(await within(nav).findByRole("link", { name })).toBeInTheDocument();
    }
    expect(screen.getByText("管理员")).toBeInTheDocument();
  });

  it("店员只看到问答；打开管理页会跳回问答页", async () => {
    const router = renderAt("/admin/users");

    expect(await screen.findByLabelText("输入问题")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/chat");
    const nav = screen.getByRole("navigation", { name: "主导航" });
    expect(within(nav).getByRole("link", { name: "问答" })).toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "知识库" })).not.toBeInTheDocument();
  });

  it("退出后清掉令牌回到登录页", async () => {
    let loggedOut = false;
    server.use(
      http.post("/api/auth/logout", () => {
        loggedOut = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderAt("/chat");

    fireEvent.click(await screen.findByRole("button", { name: "退出" }));

    expect(await screen.findByRole("button", { name: "登录" })).toBeInTheDocument();
    expect(loggedOut).toBe(true);
    expect(getToken()).toBeNull();
  });
});
