import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { signInAs } from "@/mocks/identity";
import { GrantsPanel } from "./GrantsPanel";

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <GrantsPanel knowledgeBaseId={1} />
    </QueryClientProvider>,
  );
}

describe("知识库授权", () => {
  beforeEach(() => signInAs("admin"));

  it("分两张表列出授权的角色和账号", async () => {
    renderPanel();

    expect(
      await within(await screen.findByRole("table", { name: "授权的角色" })).findByText("店长"),
    ).toBeInTheDocument();
    expect(await within(screen.getByRole("table", { name: "授权的账号" })).findByText("店员小王")).toBeInTheDocument();
  });

  it("在弹窗里授权给角色；收回角色授权", async () => {
    renderPanel();
    const roles = await screen.findByRole("table", { name: "授权的角色" });
    await within(roles).findByText("店长");

    fireEvent.click(screen.getByRole("button", { name: "添加授权" }));
    const dialog = await screen.findByRole("dialog", { name: "添加授权" });
    fireEvent.change(await within(dialog).findByLabelText("角色"), { target: { value: "3" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "授权给角色" }));
    expect(await within(roles).findByText("员工")).toBeInTheDocument();

    fireEvent.click(within(roles).getByRole("button", { name: "收回 店长" }));
    await vi.waitFor(() => expect(within(roles).queryByText("店长")).not.toBeInTheDocument());
  });

  it("授权给账号时按关键词搜索，不一次列出全部账号", async () => {
    renderPanel();
    fireEvent.click(await screen.findByRole("button", { name: "添加授权" }));
    const dialog = await screen.findByRole("dialog", { name: "添加授权" });
    fireEvent.click(within(dialog).getByRole("radio", { name: "账号" }));

    expect(await within(dialog).findByText(/共 45 个，只显示前 10 个/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText("搜索账号"), { target: { value: "zhang" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "搜索" }));
    fireEvent.click(await within(dialog).findByRole("button", { name: "授权给 店长老张" }));

    // 弹窗打开时页面其余部分对读屏隐藏，等弹窗关掉再找表格。
    expect(
      await within(await screen.findByRole("table", { name: "授权的账号" })).findByText("店长老张"),
    ).toBeInTheDocument();
  });
});
