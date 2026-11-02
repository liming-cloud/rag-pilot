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

  it("超级管理员从“管理后台”进入运营概览；菜单是树：运营概览在最上面，其余按分组排列", async () => {
    signInAs("admin");
    const router = renderAt("/chat");

    fireEvent.click(await screen.findByRole("link", { name: "管理后台" }));
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/admin/stats"));
    const menu = await screen.findByRole("navigation", { name: "管理菜单" });
    expect(
      within(menu)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["运营概览", "知识库", "知识缺口", "回答记录", "用户", "角色与权限", "模型管理", "系统设置"]);
    const groups = within(menu).getAllByRole("group");
    expect(groups.map((group) => group.getAttribute("aria-label"))).toEqual(["知识运营", "账号与权限", "系统配置"]);
    expect(
      within(groups[2]!)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["模型管理", "系统设置"]);
    // 运行的版本（D-048），测试环境没有传入版本号时为 dev。
    expect(screen.getByText("版本 dev")).toBeInTheDocument();
  });

  it("管理端首页 /admin 跳到第一个有权限的菜单", async () => {
    signInAs("admin");
    const router = renderAt("/admin");
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/admin/stats"));
  });

  it("分组可以折叠，折叠状态记在本机；当前页面所在的分组不能折叠", async () => {
    signInAs("admin");
    renderAt("/admin/models");
    const menu = await screen.findByRole("navigation", { name: "管理菜单" });

    const accounts = within(menu).getByRole("button", { name: "账号与权限" });
    fireEvent.click(accounts);
    expect(accounts).toHaveAttribute("aria-expanded", "false");
    expect(within(menu).queryByRole("link", { name: "用户" })).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem("admin.menu.collapsed")!)).toEqual(["账号与权限"]);

    const system = within(menu).getByRole("button", { name: "系统配置" });
    expect(system).toBeDisabled();
    expect(system).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(accounts);
    expect(within(menu).getByRole("link", { name: "用户" })).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem("admin.menu.collapsed")!)).toEqual([]);
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

  it("自定义角色只看到有权限的菜单，没有菜单的分组整组隐藏；打开没有权限的页面跳到第一个有权限的菜单", async () => {
    signInAs("zhang");
    const router = renderAt("/admin/users");

    const menu = await screen.findByRole("navigation", { name: "管理菜单" });
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/admin/stats"));
    expect(
      within(menu)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["运营概览", "知识缺口", "回答记录"]);
    expect(
      within(menu)
        .getAllByRole("group")
        .map((group) => group.getAttribute("aria-label")),
    ).toEqual(["知识运营"]);
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
