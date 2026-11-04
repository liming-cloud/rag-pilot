import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { signInAs } from "@/mocks/identity";
import { server } from "@/mocks/server";
import { GapsPage } from "./GapsPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <GapsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** 弹窗打开时页面其余部分对读屏隐藏，所以表格总是重新找。 */
const table = () => screen.findByRole("table", { name: "知识缺口" });
const row = async (question: string) => (await within(await table()).findByText(question)).closest("tr")!;

describe("知识缺口页", () => {
  beforeEach(() => signInAs("admin"));

  it("按待处理次数排序，同义问法归在一类，显示其他问法数、类型、检索过的知识库", async () => {
    renderPage();

    const first = await row("门店几点打烊？");
    expect(within(await table()).getAllByRole("row")[1]).toBe(first);
    expect(within(first).getByText("另有 3 种问法")).toBeInTheDocument();
    expect(within(first).getByText(/^答不上来 \d+$/)).toBeInTheDocument();
    // 当时检索的知识库，出现多的在前；超过两个时折叠成“等 N 个”。
    expect(within(first).getByText("门店运营知识库、商品知识库 等 3 个")).toBeInTheDocument();
    // 差评和拒答混在一类时两种都标出来。
    const fridge = await row("冷柜坏了找谁修？");
    expect(within(fridge).getByText(/^没帮助 \d+$/)).toBeInTheDocument();
    expect(screen.getByText(/条答不上来或差评，归成 \d+ 类/)).toBeInTheDocument();
  });

  it("关键词匹配类里任一种问法；分页", async () => {
    renderPage();
    await row("门店几点打烊？");
    expect(screen.getByText(/^共 \d+ 条$/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("问题关键词"), { target: { value: "关门" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));

    expect(await screen.findByText("共 1 条")).toBeInTheDocument();
    expect(await row("门店几点打烊？")).toBeInTheDocument();
  });

  it("打开一类：列出原始记录；用代表问题重新回答；整类标记已处理后不再出现在待处理里", async () => {
    renderPage();
    fireEvent.click(await row("冷柜坏了找谁修？"));

    const dialog = await screen.findByRole("dialog", { name: "冷柜坏了找谁修？" });
    const records = within(dialog).getByRole("table", { name: "这一类的记录" });
    expect(within(records).getAllByText("冷柜不制冷联系谁").length).toBeGreaterThan(0);
    expect(within(records).getAllByText("留言：给的电话是旧的").length).toBeGreaterThan(0);
    expect(within(records).getAllByText("检索：门店运营知识库").length).toBeGreaterThan(0);
    expect(dialog).toHaveTextContent("差评时引用了 设备维护手册.md");
    // 能上传资料的人可以直接打开检索过的库去补资料（#145 A10）。
    const upload = await within(dialog).findByRole("navigation", { name: "去补资料" });
    expect(within(upload).getByRole("link", { name: "门店运营知识库" })).toHaveAttribute("href", "/admin/knowledge/1");

    fireEvent.click(await within(dialog).findByRole("button", { name: "用代表问题重新回答" }));
    expect(await within(dialog).findByText(/现在的回答/)).toBeInTheDocument();
    expect(within(dialog).getByRole("status")).toHaveTextContent("引用 临期与报损管理.md");

    fireEvent.click(within(dialog).getByRole("button", { name: "整类标记已处理" }));
    fireEvent.change(within(dialog).getByLabelText(/处理说明/), { target: { value: "已更新报修电话" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /^确认 \d+ 条已处理$/ }));

    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await vi.waitFor(async () => expect(within(await table()).queryByText("冷柜坏了找谁修？")).not.toBeInTheDocument());
    fireEvent.change(screen.getByLabelText("处理状态"), { target: { value: "all" } });
    expect(within(await row("冷柜坏了找谁修？")).getByText("已处理")).toBeInTheDocument();
  });

  it("嵌入不可用、退回按原文分组时说明原因", async () => {
    server.use(
      http.get("/api/answers/gaps", () =>
        HttpResponse.json({
          from: "2026-10-04",
          to: "2026-11-02",
          zone: "Asia/Shanghai",
          grouping: "TEXT",
          records: 0,
          truncated: false,
          groups: [],
        }),
      ),
    );
    renderPage();

    expect(await screen.findByRole("status")).toHaveTextContent("这次按问题原文分组");
    expect(screen.getByText("这段时间没有待处理的缺口")).toBeInTheDocument();
  });

  it("没有处理、重新回答权限时只能查看", async () => {
    signInAs("zhang");
    renderPage();
    fireEvent.click(await row("门店几点打烊？"));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).queryByRole("button", { name: "整类标记已处理" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "用代表问题重新回答" })).not.toBeInTheDocument();
  });
});
