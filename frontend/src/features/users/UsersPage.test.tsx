import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { signInAs } from "@/mocks/identity";
import { UsersPage } from "./UsersPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <UsersPage />
    </QueryClientProvider>,
  );
}

/** 弹窗打开时页面其余部分对读屏隐藏，所以表格总是重新找。 */
const row = async (username: string) =>
  (await within(await screen.findByRole("table", { name: "账号" })).findByText(username)).closest("tr")!;

describe("用户管理页", () => {
  beforeEach(() => signInAs("admin"));

  it("分页列出账号的角色和状态；超级管理员不能改动", async () => {
    renderPage();

    expect(within(await row("staff01")).getByText("员工")).toBeInTheDocument();
    expect(within(await row("staff02")).getByText("已锁定")).toBeInTheDocument();
    const superAdmin = await row("admin");
    expect(within(superAdmin).getByText("超级管理员")).toBeInTheDocument();
    expect(within(superAdmin).queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByText("共 45 条")).toBeInTheDocument();
  });

  it("按关键词、角色、状态筛选", async () => {
    renderPage();
    await row("staff01");

    fireEvent.change(screen.getByLabelText("状态"), { target: { value: "locked" } });
    expect(await screen.findByText("共 1 条")).toBeInTheDocument();
    expect(await row("staff02")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("状态"), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText("角色"), { target: { value: "4" } });
    expect(await row("zhang")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("角色"), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText("关键词"), { target: { value: "store0" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    expect(await screen.findByText("共 9 条")).toBeInTheDocument();
  });

  it("新建账号在弹窗里做，要选角色；用户名重复时提示", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "新建账号" }));

    const dialog = await screen.findByRole("dialog", { name: "新建账号" });
    fireEvent.change(within(dialog).getByLabelText("用户名"), { target: { value: "staff01" } });
    fireEvent.change(within(dialog).getByLabelText("显示名"), { target: { value: "重复" } });
    fireEvent.change(within(dialog).getByLabelText("初始密码（8–64 位）"), { target: { value: "password-1" } });
    expect(within(dialog).getByRole("button", { name: "新建账号" })).toBeDisabled();
    fireEvent.click(await within(dialog).findByRole("checkbox", { name: "员工" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "新建账号" }));

    expect(await within(dialog).findByText("用户名已被使用：staff01")).toBeInTheDocument();
  });

  it("在弹窗里改角色，可以多选", async () => {
    renderPage();
    fireEvent.click(within(await row("staff01")).getByRole("button", { name: "改角色 店员小王" }));

    const dialog = await screen.findByRole("dialog", { name: "改角色：店员小王" });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: "店长" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "保存角色" }));

    expect(await within(await row("staff01")).findByText("店长")).toBeInTheDocument();
  });

  it("在弹窗里重置密码；锁定的账号可以解锁", async () => {
    renderPage();
    fireEvent.click(within(await row("staff01")).getByRole("button", { name: "重置密码 店员小王" }));
    const dialog = await screen.findByRole("dialog", { name: "重置密码：店员小王" });
    fireEvent.change(within(dialog).getByLabelText("临时密码（8–64 位）"), { target: { value: "temp-pass-1" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "重置" }));
    expect(await within(dialog).findByText(/下次登录时需要先改密码/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "完成" }));

    fireEvent.click(within(await row("staff02")).getByRole("button", { name: "解锁 店员小李" }));
    expect(await within(await row("staff02")).findByText("已启用")).toBeInTheDocument();
  });

  it("没有权限时读不到账号、看不到新建按钮", async () => {
    signInAs("zhang");
    renderPage();

    expect(await screen.findByText("没有权限执行此操作，请联系管理员")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "新建账号" })).not.toBeInTheDocument();
  });
});
