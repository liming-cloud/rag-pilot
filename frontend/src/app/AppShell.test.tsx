import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { getToken, saveToken } from "@/lib/auth";
import { signInAs } from "@/mocks/identity";
import { routes } from "./router";

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

describe("页面外壳与管理端菜单", () => {
  afterEach(() => localStorage.clear());

  it("超级管理员有“管理后台”入口，管理端菜单全部显示", async () => {
    signInAs("admin");
    renderAt("/chat");

    fireEvent.click(await screen.findByRole("link", { name: "管理后台" }));
    const menu = await screen.findByRole("navigation", { name: "管理菜单" });
    for (const name of ["知识库", "用户", "角色与权限", "回答记录", "使用统计"]) {
      expect(within(menu).getByRole("link", { name })).toBeInTheDocument();
    }
  });

  it("顶栏显示部署配置的产品名称（D-042）", async () => {
    renderAt("/chat");
    const [shellHeader] = await screen.findAllByRole("banner");
    expect(await within(shellHeader!).findByText("明答")).toBeInTheDocument();
  });

  it("员工没有管理入口；直接打开管理页回到问答页", async () => {
    const router = renderAt("/admin/users");

    expect(await screen.findByLabelText("输入问题")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/chat");
    expect(screen.queryByRole("link", { name: "管理后台" })).not.toBeInTheDocument();
  });

  it("自定义角色只看到有权限的菜单；打开没有权限的页面跳到第一个有权限的菜单", async () => {
    signInAs("zhang");
    const router = renderAt("/admin/users");

    const menu = await screen.findByRole("navigation", { name: "管理菜单" });
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/admin/answers"));
    expect(
      within(menu)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["回答记录", "使用统计"]);
  });

  it("管理员重置过密码时，登录后只能先改密码", async () => {
    signInAs("reset01");
    const router = renderAt("/chat");

    expect(await screen.findByRole("heading", { name: "修改密码" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/change-password");
    expect(screen.getByText(/管理员重置了你的密码/)).toBeInTheDocument();
  });

  it("退出后清掉令牌回到登录页", async () => {
    const router = renderAt("/chat");

    fireEvent.click(await screen.findByRole("button", { name: "退出" }));

    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/login"));
    expect(getToken()).toBeNull();
  });
});
