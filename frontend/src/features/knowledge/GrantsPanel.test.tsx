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

  it("分别列出授权的角色和账号", async () => {
    renderPanel();

    expect(
      await within(await screen.findByRole("list", { name: "已授权的角色" })).findByText("店长"),
    ).toBeInTheDocument();
    expect(await within(screen.getByRole("list", { name: "已授权的账号" })).findByText("店员小王")).toBeInTheDocument();
  });

  it("授权给角色、收回角色授权", async () => {
    renderPanel();
    const roles = await screen.findByRole("list", { name: "已授权的角色" });
    await within(roles).findByText("店长");

    fireEvent.change(screen.getByLabelText("选择要授权的角色"), { target: { value: "3" } });
    fireEvent.click(screen.getAllByRole("button", { name: "授权" })[0]!);
    expect(await within(roles).findByText("员工")).toBeInTheDocument();

    fireEvent.click(within(roles).getByRole("button", { name: "收回 店长" }));
    await vi.waitFor(() => expect(within(roles).queryByText("店长")).not.toBeInTheDocument());
  });
});
