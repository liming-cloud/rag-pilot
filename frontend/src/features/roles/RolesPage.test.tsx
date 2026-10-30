import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { signInAs } from "@/mocks/identity";
import { RolesPage } from "./RolesPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <RolesPage />
    </QueryClientProvider>,
  );
}

describe("角色与权限页", () => {
  beforeEach(() => signInAs("admin"));

  it("超级管理员只读，全部权限都勾上", async () => {
    renderPage();

    const editor = await screen.findByRole("form", { name: "角色权限" });
    expect(within(editor).getByText(/超级管理员拥有全部权限/)).toBeInTheDocument();
    const boxes = await within(editor).findAllByRole("checkbox");
    expect(boxes.every((box) => box.getAttribute("aria-checked") === "true")).toBe(true);
    expect(boxes.every((box) => box.hasAttribute("disabled"))).toBe(true);
    expect(within(editor).queryByRole("button", { name: "保存" })).not.toBeInTheDocument();
  });

  it("勾按钮权限会自动勾上所在菜单；新建角色后出现在列表里", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "新建角色" }));
    const editor = await screen.findByRole("form", { name: "角色权限" });
    fireEvent.change(within(editor).getByLabelText("角色名称"), { target: { value: "区域经理" } });

    fireEvent.click(await within(editor).findByRole("checkbox", { name: "导出评测题" }));
    expect(within(editor).getByRole("checkbox", { name: /回答记录/ })).toHaveAttribute("aria-checked", "true");
    fireEvent.click(within(editor).getByRole("button", { name: "新建角色" }));

    expect(await within(screen.getByRole("navigation", { name: "角色" })).findByText("区域经理")).toBeInTheDocument();
  });

  it("还有账号的角色不能删，说明原因", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /店长/ }));

    const editor = await screen.findByRole("form", { name: "角色权限" });
    expect(await within(editor).findByRole("button", { name: "删除角色" })).toBeDisabled();
    expect(within(editor).getByText(/还有 1 个账号属于这个角色/)).toBeInTheDocument();
  });
});
