import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/mocks/server";
import { AnswersPage } from "./AnswersPage";
import { signInAs } from "@/mocks/identity";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AnswersPage />
    </QueryClientProvider>,
  );
}

describe("回答记录页", () => {
  // 管理页按权限显示按钮，以有全部管理权限的超级管理员测试页面本身的行为。
  beforeEach(() => signInAs("admin"));

  /** 弹窗打开时页面其余部分对读屏隐藏，所以表格总是重新找。 */
  const row = async (question: string) =>
    (await within(await screen.findByRole("table", { name: "回答记录" })).findByText(question)).closest("tr")!;

  async function open(question: string) {
    fireEvent.click(await row(question));
    return screen.findByRole("dialog", { name: question });
  }

  it("默认看最近 30 天待处理的差评，分页显示一行摘要", async () => {
    renderPage();

    const first = await row("临期商品打几折？");
    expect(within(first).getByText("店员小王")).toBeInTheDocument();
    expect(within(first).getByText("没帮助")).toBeInTheDocument();
    expect(screen.queryByText("门店几点打烊？")).not.toBeInTheDocument();
    expect(screen.getByText(/共 \d+ 条/)).toBeInTheDocument();
  });

  it("点一行在弹窗里看完整问答、留言和引用", async () => {
    renderPage();
    const dialog = await open("临期商品打几折？");

    expect(within(dialog).getByText("员工留言：门店规定是五折")).toBeInTheDocument();
    expect(within(dialog).getByText(/引用：临期与报损管理\.md/)).toBeInTheDocument();
  });

  it("按类型、关键词筛选；答不上来的记录能看到系统改写后的问题", async () => {
    renderPage();
    await row("临期商品打几折？");

    fireEvent.change(screen.getByLabelText("类型"), { target: { value: "refused" } });
    fireEvent.change(screen.getByLabelText("问题关键词"), { target: { value: "打烊" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));

    expect(await screen.findByText("共 1 条")).toBeInTheDocument();
    const dialog = await open("门店几点打烊？");
    expect(within(dialog).getByText("系统改写为：门店的营业结束时间是几点？")).toBeInTheDocument();
  });

  it("导出当前筛选条件下的记录为评测题草稿，等待人工标注", async () => {
    let exported: Blob | undefined;
    vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => {
      exported = blob as Blob;
      return "blob:eval";
    });
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    renderPage();
    await row("临期商品打几折？");
    fireEvent.change(screen.getByLabelText("问题关键词"), { target: { value: "临期商品打几折" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await screen.findByText("共 1 条");

    fireEvent.click(screen.getByRole("button", { name: "导出为评测题草稿" }));

    await vi.waitFor(() => expect(exported).toBeDefined());
    const cases = JSON.parse(await readText(exported!)) as Record<string, unknown>[];
    expect(cases).toEqual([
      expect.objectContaining({
        id: "真实-601",
        question: "临期商品打几折？",
        kind: "answerable",
        expectedDocument: "临期与报损管理.md",
        keywords: [],
        style: "真实",
      }),
    ]);
    expect(String(cases[0]!.note)).toContain("门店规定是五折");
  });

  it("在弹窗里重新回答，显示现在的回答", async () => {
    renderPage();
    const dialog = await open("临期商品打几折？");

    fireEvent.click(within(dialog).getByRole("button", { name: "重新回答" }));

    expect(await within(dialog).findByText("现在的回答")).toBeInTheDocument();
    expect(within(dialog).getByText(/按原价五折销售/)).toBeInTheDocument();
  });

  it("标记已处理后从待处理里消失；切到已处理能看到说明并重新打开", async () => {
    renderPage();
    const dialog = await open("临期商品打几折？");

    fireEvent.click(within(dialog).getByRole("button", { name: "标记已处理" }));
    fireEvent.change(within(dialog).getByLabelText("处理说明（选填）"), {
      target: { value: "已上传 2026 版临期规定" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "确认已处理" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await vi.waitFor(() => expect(screen.queryByText("临期商品打几折？")).not.toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("处理状态"), { target: { value: "resolved" } });
    const resolved = await open("临期商品打几折？");
    expect(within(resolved).getByText("处理说明：已上传 2026 版临期规定")).toBeInTheDocument();
    fireEvent.click(within(resolved).getByRole("button", { name: "重新打开" }));
    await vi.waitFor(() => expect(screen.queryByText("临期商品打几折？")).not.toBeInTheDocument());
  });

  it("接口失败时给出中文提示", async () => {
    server.use(http.get("/api/answers", () => new HttpResponse(null, { status: 500 })));
    renderPage();
    expect(await screen.findByText("读取回答记录失败")).toBeInTheDocument();
  });
});

/** jsdom 的 Blob 没有 text()，用 FileReader 读。 */
function readText(blob: Blob) {
  return new Promise<string>((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.readAsText(blob);
  });
}
