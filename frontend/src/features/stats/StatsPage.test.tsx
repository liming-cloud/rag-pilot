import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { signInAs } from "@/mocks/identity";
import { server } from "@/mocks/server";
import { StatsPage } from "./StatsPage";

function renderPage(path = "/") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: "/", element: <StatsPage /> }], { initialEntries: [path] });
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe("运营概览页", () => {
  it("默认看近 30 天：汇总、拒答原因、知识缺口、引用最多的文档", async () => {
    renderPage();

    const totals = await screen.findByRole("list", { name: "汇总" });
    expect(within(totals).getByText("提问")).toBeInTheDocument();
    expect(within(totals).getByText("拒答率")).toBeInTheDocument();
    expect(within(totals).getByText("差评率")).toBeInTheDocument();
    // 产品指标（#145 A11）。
    expect(within(totals).getByText("提问人数占比")).toBeInTheDocument();
    expect(within(totals).getByText("人均提问")).toBeInTheDocument();
    expect(within(totals).getByText("短时间重复提问")).toBeInTheDocument();
    expect(within(totals).getByText(/36 位能用问答的员工里 \d+ 位问过/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "近 30 天" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("table", { name: "每日趋势" })).toBeInTheDocument();
    expect(screen.getByText("相关度低于门槛")).toBeInTheDocument();
    expect(screen.getByText("门店几点打烊？")).toBeInTheDocument();
    expect(screen.getByText("临期与报损管理.md")).toBeInTheDocument();
    expect(screen.getByText(/（北京时间）$/)).toBeInTheDocument();
  });

  it("有回答记录权限时，“待处理”链到全部待处理的回答记录；没有权限时只显示数字", async () => {
    signInAs("admin");
    const router = renderPage();

    const pending = await screen.findByRole("link", { name: /^待处理 \d+，去处理$/ });
    fireEvent.click(pending);
    await vi.waitFor(() => expect(router.state.location.search).toBe("?filter=all"));
  });

  it("没有回答记录权限时，“待处理”不是链接", async () => {
    signInAs("staff01");
    renderPage();

    await screen.findByRole("list", { name: "汇总" });
    expect(screen.getByText("待处理")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("比例按提问数和已回答数计算；没有提问时显示“-”", async () => {
    server.use(
      http.get("/api/answers/stats", () =>
        HttpResponse.json({
          from: "2026-10-22",
          to: "2026-10-28",
          zone: "Asia/Singapore",
          totals: { questions: 40, askers: 6, refused: 10, rated: 12, liked: 9, disliked: 3, openReviews: 5 },
          daily: [],
          refusalReasons: [],
          previous: null,
          topGaps: [],
          topCitedDocuments: [],
        }),
      ),
    );
    renderPage();

    const totals = await screen.findByRole("list", { name: "汇总" });
    expect(within(within(totals).getByText("拒答率").closest("li")!).getByText("25.0%")).toBeInTheDocument();
    // 评价率和差评率的分母是给出了回答的 30 题。
    expect(within(within(totals).getByText("评价率").closest("li")!).getByText("40.0%")).toBeInTheDocument();
    expect(within(within(totals).getByText("差评率").closest("li")!).getByText("10.0%")).toBeInTheDocument();
    expect(screen.getByText("2026-10-22 至 2026-10-28（新加坡时间）")).toBeInTheDocument();

    server.use(
      http.get("/api/answers/stats", () =>
        HttpResponse.json({
          from: "2026-10-22",
          to: "2026-10-28",
          zone: "Asia/Singapore",
          totals: {
            questions: 0,
            askers: 0,
            refused: 0,
            rated: 0,
            liked: 0,
            disliked: 0,
            openReviews: 0,
            activeStaff: 30,
            repeatQuestions: 0,
          },
          daily: [],
          refusalReasons: [],
          previous: null,
          topGaps: [],
          topCitedDocuments: [],
        }),
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "近 7 天" }));
    expect(await screen.findByText("这段时间还没有提问。")).toBeInTheDocument();
    // 拒答率、评价率、好评率、差评率、人均提问、短时间重复提问没有分母；提问人数占比是 0.0%。
    expect(screen.getAllByText("-")).toHaveLength(6);
    expect(within(screen.getByRole("list", { name: "汇总" })).getByText("0.0%")).toBeInTheDocument();
  });

  it("有知识库权限时提醒即将到期和已失效的资料，链到知识库", async () => {
    signInAs("admin");
    renderPage();

    const section = await screen.findByRole("region", { name: "资料有效期" });
    const notice = within(section).getByRole("status");
    expect(notice).toHaveTextContent("2 份将在 30 天内到期");
    expect(notice).toHaveTextContent("1 份已失效，不再参与回答");
    expect(within(notice).getByRole("link", { name: /去知识库处理/ })).toHaveAttribute("href", "/admin/knowledge");
    // 列出具体的资料，已失效的最早，点进去按有效期筛选。
    const items = within(section).getAllByRole("listitem");
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveTextContent("已失效临期与报损管理（旧版）.md");
    expect(within(items[0]!).getByRole("link")).toHaveAttribute("href", "/admin/knowledge/1?validity=EXPIRED");
  });

  it("没有知识库权限时不显示资料到期提醒", async () => {
    signInAs("zhang");
    renderPage();

    await screen.findByRole("list", { name: "汇总" });
    expect(screen.queryByText("资料有效期")).not.toBeInTheDocument();
  });

  it("按周查看：选最近 12 周里的一周，与上一周对比；可以打印", async () => {
    signInAs("admin");
    const print = vi.spyOn(window, "print").mockImplementation(() => {});
    renderPage();
    await screen.findByRole("list", { name: "汇总" });

    const weeks = screen.getByLabelText("按周查看");
    expect(within(weeks).getAllByRole("option")).toHaveLength(13);
    const lastWeek = within(weeks).getByRole<HTMLOptionElement>("option", { name: /（上周）$/ });
    fireEvent.change(weeks, { target: { value: lastWeek.value } });

    expect(await screen.findByRole("heading", { name: "运营周报" })).toBeInTheDocument();
    expect(await screen.findByText(/，与上一周对比$/)).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(8);
    const totals = screen.getByRole("list", { name: "汇总" });
    expect(within(within(totals).getByText("提问").closest("li")!).getByText(/^比上周 [+-]?\d+$/)).toBeInTheDocument();
    expect(within(within(totals).getByText("拒答率").closest("li")!).getByText(/个百分点$/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "近 30 天" })).toHaveAttribute("aria-pressed", "false");

    fireEvent.click(screen.getByRole("button", { name: "打印 / 存为 PDF" }));
    expect(print).toHaveBeenCalled();
    print.mockRestore();
  });

  it("导出问答明细：有 stats:export 时下载后端给出的 CSV；没有时看不到按钮", async () => {
    signInAs("admin");
    const created: Blob[] = [];
    const createObjectURL = vi.fn((blob: Blob) => {
      created.push(blob);
      return "blob:csv";
    });
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    renderPage();
    await screen.findByRole("list", { name: "汇总" });

    fireEvent.click(screen.getByRole("button", { name: "导出问答明细（CSV）" }));

    await vi.waitFor(() => expect(click).toHaveBeenCalled());
    const link = click.mock.contexts[0] as HTMLAnchorElement;
    expect(link.download).toMatch(/^运营明细_\d{4}-\d{2}-\d{2}_\d{4}-\d{2}-\d{2}\.csv$/);
    expect(created[0]!.type).toContain("text/csv");
    click.mockRestore();
  });

  it("没有导出权限时看不到导出按钮", async () => {
    signInAs("staff01");
    renderPage();
    await screen.findByRole("list", { name: "汇总" });
    expect(screen.queryByRole("button", { name: "导出问答明细（CSV）" })).not.toBeInTheDocument();
  });

  it("有回答记录权限时，知识缺口排行链到知识缺口页", async () => {
    signInAs("admin");
    renderPage();
    await screen.findByText("门店几点打烊？");
    expect(screen.getByRole("link", { name: /查看全部/ })).toHaveAttribute("href", "/admin/gaps");
  });
});
