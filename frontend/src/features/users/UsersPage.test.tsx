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

const row = async (name: string) => (await screen.findByText(name)).closest("tr")!;

describe("用户管理页", () => {
  beforeEach(() => signInAs("admin"));

  it("列出账号的角色和状态；超级管理员不能改动", async () => {
    renderPage();

    expect(within(await row("店员小王")).getByText("员工")).toBeInTheDocument();
    expect(within(await row("店员小李")).getByText("已锁定")).toBeInTheDocument();
    const superAdmin = await row("admin");
    expect(within(superAdmin).getByText("超级管理员")).toBeInTheDocument();
    expect(within(superAdmin).queryByRole("button")).not.toBeInTheDocument();
  });

  it("新建账号要选角色；用户名重复时提示", async () => {
    renderPage();
    const form = await screen.findByRole("form", { name: "新建账号" });
    fireEvent.change(within(form).getByLabelText("用户名"), { target: { value: "staff01" } });
    fireEvent.change(within(form).getByLabelText("显示名"), { target: { value: "重复" } });
    fireEvent.change(within(form).getByLabelText("初始密码（8–64 位）"), { target: { value: "password-1" } });
    expect(within(form).getByRole("button", { name: "新建账号" })).toBeDisabled();

    fireEvent.click(await within(form).findByRole("checkbox", { name: "员工" }));
    fireEvent.click(within(form).getByRole("button", { name: "新建账号" }));

    expect(await screen.findByText("用户名已被使用：staff01")).toBeInTheDocument();
  });

  it("改角色：可以多选（越权分配由后端拦截，见后端测试）", async () => {
    renderPage();
    fireEvent.click(within(await row("店员小王")).getByRole("button", { name: "改角色" }));
    // 新建账号的表单里也有“店长”，取改角色面板里的那个。
    const newAccountForm = screen.getByRole("form", { name: "新建账号" });
    const boxes = await screen.findAllByRole("checkbox", { name: "店长" });
    fireEvent.click(boxes.find((box) => !newAccountForm.contains(box))!);
    fireEvent.click(screen.getByRole("button", { name: "保存角色" }));

    expect(await within(await row("店员小王")).findByText("员工、店长")).toBeInTheDocument();
  });

  it("重置密码后提示把临时密码告诉员工；锁定的账号可以解锁", async () => {
    renderPage();
    fireEvent.click(within(await row("店员小王")).getByRole("button", { name: "重置密码" }));
    fireEvent.change(await screen.findByLabelText("给 店员小王 的临时密码（8–64 位）"), {
      target: { value: "temp-pass-1" },
    });
    fireEvent.click(screen.getByRole("button", { name: "重置" }));
    expect(await screen.findByText(/下次登录时需要先改密码/)).toBeInTheDocument();

    fireEvent.click(within(await row("店员小李")).getByRole("button", { name: "解锁" }));
    expect(await within(await row("店员小李")).findByText("已启用")).toBeInTheDocument();
  });

  it("没有按钮权限时看不到对应按钮", async () => {
    signInAs("zhang");
    renderPage();

    expect(await screen.findByText("没有权限执行此操作，请联系管理员")).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "新建账号" })).not.toBeInTheDocument();
  });
});
