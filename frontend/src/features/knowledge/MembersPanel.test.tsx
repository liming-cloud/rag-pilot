import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MembersPanel } from "./MembersPanel";

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MembersPanel knowledgeBaseId={1} />
    </QueryClientProvider>,
  );
}

describe("成员面板", () => {
  it("列出成员的显示名、用户名和角色", async () => {
    renderPanel();

    const members = await screen.findByRole("list", { name: "成员" });
    expect(await within(members).findByText("管理员")).toBeInTheDocument();
    expect(within(members).getByText(/admin · 管理员账号/)).toBeInTheDocument();
    expect(within(members).getByText("店员小王")).toBeInTheDocument();
  });

  it("授予未授权的账号，授予后不再出现在可选项里", async () => {
    renderPanel();
    const select = await screen.findByLabelText("选择账号");
    await within(select).findByRole("option", { name: "店员小李（staff02）" });

    fireEvent.change(select, { target: { value: "3" } });
    fireEvent.click(screen.getByRole("button", { name: "授权" }));

    const members = screen.getByRole("list", { name: "成员" });
    expect(await within(members).findByText("店员小李")).toBeInTheDocument();
    expect(within(select).queryByRole("option", { name: "店员小李（staff02）" })).not.toBeInTheDocument();
  });

  it("撤销成员", async () => {
    renderPanel();
    fireEvent.click(await screen.findByRole("button", { name: "撤销 店员小王" }));

    const members = screen.getByRole("list", { name: "成员" });
    await vi.waitFor(() => expect(within(members).queryByText("店员小王")).not.toBeInTheDocument());
  });
});
