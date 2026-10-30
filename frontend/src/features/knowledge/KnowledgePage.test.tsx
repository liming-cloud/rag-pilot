import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { signInAs } from "@/mocks/identity";
import { server } from "@/mocks/server";
import { KnowledgeDetailPage } from "./KnowledgeDetailPage";
import { KnowledgePage } from "./KnowledgePage";

function renderAt(path = "/admin/knowledge") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: "/admin/knowledge", element: <KnowledgePage /> },
      { path: "/admin/knowledge/:knowledgeBaseId", element: <KnowledgeDetailPage /> },
    ],
    { initialEntries: [path] },
  );
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe("知识库列表", () => {
  // 管理页按权限显示按钮，以有全部管理权限的超级管理员测试页面本身的行为。
  beforeEach(() => signInAs("admin"));

  it("分页列出知识库，显示文档数、失败数和授权情况", async () => {
    renderAt();

    const table = await screen.findByRole("table", { name: "知识库" });
    const row = (await within(table).findByRole("link", { name: "门店运营知识库" })).closest("tr")!;
    expect(within(row).getByText("32 份")).toBeInTheDocument();
    expect(within(row).getByText("1 份失败")).toBeInTheDocument();
    expect(within(row).getByText("1 个角色 · 2 个账号")).toBeInTheDocument();
    expect(screen.getByText("共 24 条")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(await within(table).findByText("区域 22 门店规范")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "第 2 页" })).toHaveAttribute("aria-current", "page");
  });

  it("按名称筛选", async () => {
    renderAt();
    await screen.findByRole("link", { name: "门店运营知识库" });

    fireEvent.change(screen.getByLabelText("名称"), { target: { value: "商品" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));

    expect(await screen.findByText("共 1 条")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "商品知识库" })).toBeInTheDocument();
  });

  it("新建知识库在弹窗里做，建好后进入它的详情", async () => {
    const router = renderAt();
    fireEvent.click(await screen.findByRole("button", { name: "新建知识库" }));

    const dialog = await screen.findByRole("dialog", { name: "新建知识库" });
    fireEvent.change(within(dialog).getByLabelText("名称"), { target: { value: "促销活动" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "新建" }));

    expect(await screen.findByRole("heading", { name: "促销活动" })).toBeInTheDocument();
    expect(router.state.location.pathname).toMatch(/^\/admin\/knowledge\/\d+$/);
    expect(await screen.findByText("还没有文档，先上传一份吧。")).toBeInTheDocument();
  });

  it("详情分“文档”“授权”两个标签，标签记在地址里", async () => {
    const router = renderAt("/admin/knowledge/1");

    expect(await screen.findByRole("tab", { name: "文档" })).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByRole("tab", { name: "授权" }));

    expect(await screen.findByRole("table", { name: "授权的角色" })).toBeInTheDocument();
    expect(router.state.location.search).toBe("?tab=grants");
  });

  it("接口失败时给出中文提示", async () => {
    server.use(http.get("/api/knowledge-bases/page", () => new HttpResponse(null, { status: 500 })));
    renderAt();
    expect(await screen.findByText("读取知识库失败，请稍后重试。")).toBeInTheDocument();
  });
});
