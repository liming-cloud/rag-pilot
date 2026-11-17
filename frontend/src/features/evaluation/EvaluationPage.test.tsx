import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { signInAs, state } from "@/mocks/identity";
import { EvaluationPage } from "./EvaluationPage";
import { RunPage } from "./RunPage";
import { SetDetailPage } from "./SetDetailPage";

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: "/admin/evaluation", element: <EvaluationPage /> },
      { path: "/admin/evaluation/sets/:setId", element: <SetDetailPage /> },
      { path: "/admin/evaluation/runs/:runId", element: <RunPage /> },
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

const setRow = async (name: string) =>
  (await within(await screen.findByRole("table", { name: "题集" })).findByText(name)).closest("tr")!;

describe("知识质量", () => {
  beforeEach(() => signInAs("admin"));

  it("题集列表显示题数、过期题数、涉及的库和最近一次运行的新失败", async () => {
    renderAt("/admin/evaluation");

    const row = await setRow("门店运营回归");
    expect(within(row).getByText("15")).toBeInTheDocument();
    expect(within(row).getByText("过期 1")).toBeInTheDocument();
    expect(within(row).getByText(/门店运营知识库、商品知识库 等 3 个/)).toBeInTheDocument();
    expect(within(row).getByText(/^新失败 \d+$/)).toBeInTheDocument();
  });

  it("无权访问的库里的题当作不存在：题数、题目列表都不含", async () => {
    // 店长只通过角色访问门店运营知识库；给店长角色加上查看权限。
    state.roles.find((role) => role.id === 4)!.permissions.push("eval:view");
    signInAs("zhang");
    renderAt("/admin/evaluation/sets/1");

    const table = await screen.findByRole("table", { name: "标准题" });
    expect(await within(table).findByText("临期商品打几折？")).toBeInTheDocument();
    expect(within(table).queryByText("临期与报损在商品库里怎么规定？")).not.toBeInTheDocument();
    expect(within(table).queryByText("区域 1 的门店几点开门？")).not.toBeInTheDocument();
    // 没有管理和运行权限，按钮不显示。
    expect(screen.queryByRole("button", { name: "运行" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "添加标准题" })).not.toBeInTheDocument();
  });

  it("试问列出召回的原文；存为标准题时默认期望被引用的文档", async () => {
    renderAt("/admin/evaluation?tab=trial&question=临期商品打几折？");

    expect(await screen.findByLabelText("问题")).toHaveValue("临期商品打几折？");
    fireEvent.click(await screen.findByRole("button", { name: "试问" }));
    const passages = await screen.findByRole("table", { name: "召回的原文" });
    expect(within(passages).getAllByText("已引用")).toHaveLength(2);

    fireEvent.click(screen.getByRole("button", { name: "存为标准题" }));
    const dialog = await screen.findByRole("dialog", { name: "添加标准题" });
    const chosen = within(dialog).getByLabelText("已选的期望文档");
    expect(within(chosen).getByText("临期与报损管理.md")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "保存" }));
    await screen.findByRole("tab", { name: "试问" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("过期题标出来；运行前显示预估费用和跳过的过期题", async () => {
    const router = renderAt("/admin/evaluation/sets/1");

    const table = await screen.findByRole("table", { name: "标准题" });
    const stale = (await within(table).findByText("旧版收银流程第三步是什么？")).closest("tr")!;
    expect(within(stale).getByText(/过期：期望文档已删除/)).toBeInTheDocument();

    fireEvent.click(await screen.findByRole("button", { name: "运行" }));
    const dialog = await screen.findByRole("dialog", { name: "运行题集" });
    expect(await within(dialog).findByText("14 道题")).toBeInTheDocument();
    expect(within(dialog).getByText("1 道过期题")).toBeInTheDocument();
    expect(within(dialog).getByText(/约 0\.17 元/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "开始运行" }));
    await screen.findByRole("heading", { name: "运行结果" });
    expect(router.state.location.pathname).toMatch(/^\/admin\/evaluation\/runs\/\d+$/);
  });

  it("运行结果默认先看新失败的题，列出没召回的文档", async () => {
    renderAt("/admin/evaluation/runs/1001");

    expect(await screen.findByRole("button", { name: /^新失败 \d+$/, pressed: true })).toBeInTheDocument();
    const results = screen.getByRole("table", { name: "每道题的结果" });
    expect(within(results).getAllByText("新失败").length).toBeGreaterThan(0);
    expect(within(results).queryByText("新通过")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^全部 \d+$/ }));
    expect(await within(screen.getByRole("table", { name: "每道题的结果" })).findAllByText("新通过")).not.toHaveLength(0);
  });
});
