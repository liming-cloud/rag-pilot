import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { UsersPage } from "./UsersPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <UsersPage />
    </QueryClientProvider>,
  );
}

function fill(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

describe("账号管理页", () => {
  it("列出账号的角色和状态", async () => {
    renderPage();

    const row = (await screen.findByText("店员小王")).closest("li")!;
    expect(within(row).getByText(/staff01 · 店员账号/)).toBeInTheDocument();
    expect(within(row).getByText("已启用")).toBeInTheDocument();
  });

  it("新建账号；用户名重复时提示", async () => {
    renderPage();
    await screen.findByText("店员小王");

    fill("用户名", "staff09");
    fill("显示名", "店员小赵");
    fill("初始密码", "staff-password");
    fireEvent.click(screen.getByRole("button", { name: "新建账号" }));
    expect(await screen.findByText("店员小赵")).toBeInTheDocument();

    fill("用户名", "staff01");
    fill("显示名", "重名");
    fill("初始密码", "staff-password");
    fireEvent.click(screen.getByRole("button", { name: "新建账号" }));
    expect(await screen.findByText("用户名已被使用：staff01")).toBeInTheDocument();
  });

  it("停用后显示已停用，可以再启用", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "停用 店员小王" }));

    const row = (await screen.findByText("已停用")).closest("li")!;
    expect(within(row).getByText("店员小王")).toBeInTheDocument();
    fireEvent.click(within(row).getByRole("button", { name: "启用 店员小王" }));
    expect(await within(row).findByText("已启用")).toBeInTheDocument();
  });
});
