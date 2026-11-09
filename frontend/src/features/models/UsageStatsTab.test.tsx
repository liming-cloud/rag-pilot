import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { signInAs } from "@/mocks/identity";
import { resetModels } from "@/mocks/models";
import { ModelsPage } from "./ModelsPage";

function renderStats() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter([{ path: "/admin/models", element: <ModelsPage /> }], {
    initialEntries: ["/admin/models?tab=stats"],
  });
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

const usageTable = async () => screen.findByRole("table", { name: "用量统计" });

describe("模型管理页的用量标签", () => {
  beforeEach(() => {
    signInAs("admin");
    resetModels();
  });

  it("单价表在最上面；下面是分页的明细列表：日期、用途、模型、服务商等列，最新的在前，合计在表上方", async () => {
    renderStats();
    const prices = await screen.findByRole("table", { name: "模型单价" });
    const table = await usageTable();
    expect(prices.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "日期",
      "用途",
      "模型",
      "服务商",
      "调用次数",
      "输入 token",
      "输出 token",
      "估算费用",
    ]);
    const rows = await vi.waitFor(() => {
      const found = within(table).getAllByRole("row");
      expect(found.length).toBeGreaterThan(2);
      return found;
    });
    expect(rows).toHaveLength(21);
    const firstCell = (row: HTMLElement) => within(row).getAllByRole("cell").at(0)?.textContent ?? "";
    expect(firstCell(rows[1]!) >= firstCell(rows[2]!)).toBe(true);
    expect(within(rows[1]!).getByText(/api\./)).toBeInTheDocument();
    expect(screen.getByLabelText("合计")).toHaveTextContent("合计：调用");
    // 轻量任务、入库向量化的模型没填单价，合计的费用是“不全”的。
    expect(within(screen.getByLabelText("合计")).getByText("缺单价")).toBeInTheDocument();
    expect(screen.getByText(/共 \d+ 条/)).toBeInTheDocument();
  });

  it("翻页后换一批行；换统计方式回到第 1 页：按用途用中文名、用得多的在前，按月每月一行", async () => {
    renderStats();
    const table = await usageTable();
    await within(table).findAllByText("回答");
    fireEvent.click(await screen.findByRole("button", { name: /下一页/ }));
    await vi.waitFor(() => expect(screen.getByRole("button", { name: /上一页/ })).toBeEnabled());

    fireEvent.change(screen.getByLabelText("统计方式"), { target: { value: "PURPOSE" } });
    expect(await within(table).findByText("入库向量化")).toBeInTheDocument();
    const purposes = within(table).getAllByRole("row").slice(1).map((row) => within(row).getAllByRole("cell")[0]!.textContent);
    expect(purposes[0]).toBe("回答");
    expect(purposes).toEqual(expect.arrayContaining(["回答", "重排", "轻量任务", "入库向量化"]));

    fireEvent.change(screen.getByLabelText("统计方式"), { target: { value: "MONTH" } });
    await vi.waitFor(() => expect(within(table).getAllByRole("row")[1]!.textContent).toMatch(/^\d{4}-\d{2}/));
  });

  it("按单价估算费用：填上单价后，缺单价的标记消失、费用变大", async () => {
    renderStats();
    fireEvent.change(await screen.findByLabelText("统计方式"), { target: { value: "PURPOSE" } });
    const table = await usageTable();
    const light = (await within(table).findByText("轻量任务")).closest("tr")!;
    expect(within(light).getByText("缺单价")).toBeInTheDocument();
    expect(within(light).getByText("¥0.00")).toBeInTheDocument();

    const prices = screen.getByRole("table", { name: "模型单价" });
    fireEvent.click(await within(prices).findByRole("button", { name: "设置 Qwen/Qwen2.5-7B-Instruct 的单价" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/输入单价/), { target: { value: "0.5" } });
    fireEvent.change(within(dialog).getByLabelText(/输出单价/), { target: { value: "1" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "保存" }));

    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await vi.waitFor(() => expect(within(light).queryByText("缺单价")).not.toBeInTheDocument());
    expect(within(light).queryByText("¥0.00")).not.toBeInTheDocument();
    const qwen = within(prices).getByTitle("Qwen/Qwen2.5-7B-Instruct").closest("tr")!;
    expect(within(qwen).getByText("0.5")).toBeInTheDocument();
    expect(within(qwen).getByText("1")).toBeInTheDocument();
  });

  it("单价不能填负数：保存按钮不可点", async () => {
    renderStats();
    const prices = await screen.findByRole("table", { name: "模型单价" });
    fireEvent.click(await within(prices).findByRole("button", { name: "设置 deepseek-chat 的单价" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/输入单价/), { target: { value: "-1" } });

    expect(within(dialog).getByRole("button", { name: "保存" })).toBeDisabled();
  });
});
