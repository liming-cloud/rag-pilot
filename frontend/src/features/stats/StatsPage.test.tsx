import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/mocks/server";
import { StatsPage } from "./StatsPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <StatsPage />
    </QueryClientProvider>,
  );
}

describe("使用统计页", () => {
  it("默认看近 30 天：汇总、拒答原因、答不上来的问题、引用最多的文档", async () => {
    renderPage();

    const totals = await screen.findByRole("list", { name: "汇总" });
    expect(within(totals).getByText("提问")).toBeInTheDocument();
    expect(within(totals).getByText("拒答率")).toBeInTheDocument();
    expect(within(totals).getByText("差评率")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "近 30 天" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("table", { name: "每日趋势" })).toBeInTheDocument();
    expect(screen.getByText("相关度低于门槛")).toBeInTheDocument();
    expect(screen.getByText("门店几点打烊？")).toBeInTheDocument();
    expect(screen.getByText("临期与报损管理.md")).toBeInTheDocument();
  });

  it("比例按提问数和已回答数计算；没有提问时显示“-”", async () => {
    server.use(
      http.get("/api/answers/stats", () =>
        HttpResponse.json({
          from: "2026-10-22",
          to: "2026-10-28",
          totals: { questions: 40, askers: 6, refused: 10, rated: 12, liked: 9, disliked: 3, openReviews: 5 },
          daily: [],
          refusalReasons: [],
          topRefusedQuestions: [],
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

    server.use(
      http.get("/api/answers/stats", () =>
        HttpResponse.json({
          from: "2026-10-22",
          to: "2026-10-28",
          totals: { questions: 0, askers: 0, refused: 0, rated: 0, liked: 0, disliked: 0, openReviews: 0 },
          daily: [],
          refusalReasons: [],
          topRefusedQuestions: [],
          topCitedDocuments: [],
        }),
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "近 7 天" }));
    expect(await screen.findByText("这段时间还没有提问。")).toBeInTheDocument();
    expect(screen.getAllByText("-")).toHaveLength(3);
  });
});
