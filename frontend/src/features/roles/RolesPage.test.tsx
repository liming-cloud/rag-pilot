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

const menuRow = async (menu: string) =>
  (await within(await screen.findByRole("table", { name: "权限" })).findByText(menu)).closest("tr")!;

describe("角色与权限页", () => {
  beforeEach(() => signInAs("admin"));

  it("超级管理员只读，没有“编辑权限”", async () => {
    renderPage();

    expect(await screen.findByText(/超级管理员拥有全部权限/)).toBeInTheDocument();
    expect(within(await menuRow("角色与权限")).getByText("新建角色")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "编辑权限" })).not.toBeInTheDocument();
  });

  it("只读时每个菜单一行，只列出有的权限，没有的写“无”", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /店长/ }));

    const answers = await menuRow("回答记录");
    expect(within(answers).getByText("可以")).toBeInTheDocument();
    expect(within(answers).getByText("无")).toBeInTheDocument();
    expect(within(await menuRow("使用统计")).getByText("导出")).toBeInTheDocument();
    expect(within(await menuRow("用户管理")).getByText("不可以")).toBeInTheDocument();
  });

  it("编辑时点按钮权限标签，自动开放所在菜单；保存后回到只读", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /店长/ }));
    fireEvent.click(await screen.findByRole("button", { name: "编辑权限" }));

    const answers = await menuRow("回答记录");
    fireEvent.click(within(answers).getByRole("button", { name: "导出评测题" }));
    expect(within(answers).getByRole("button", { name: "导出评测题" })).toHaveAttribute("aria-pressed", "true");
    const knowledge = await menuRow("知识库管理");
    fireEvent.click(within(knowledge).getByRole("button", { name: "上传文档" }));
    expect(within(knowledge).getByRole("checkbox", { name: "可进入 知识库管理" })).toHaveAttribute(
      "aria-checked",
      "true",
    );
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await within(await menuRow("知识库管理")).findByText("上传文档")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "编辑权限" })).toBeInTheDocument();
  });

  it("新建角色后出现在列表里", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "新建角色" }));
    fireEvent.change(await screen.findByLabelText("角色名称"), { target: { value: "区域经理" } });
    fireEvent.click(within(await menuRow("使用统计")).getByRole("button", { name: "导出" }));
    fireEvent.click(screen.getAllByRole("button", { name: "新建角色" }).at(-1)!);

    expect(await within(screen.getByRole("navigation", { name: "角色" })).findByText("区域经理")).toBeInTheDocument();
  });

  it("还有账号的角色不能删，说明原因", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /店长/ }));

    expect(await screen.findByRole("button", { name: "删除角色" })).toBeDisabled();
    expect(screen.getByText(/还有 1 个账号属于这个角色/)).toBeInTheDocument();
  });
});
