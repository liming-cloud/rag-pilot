import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/mocks/server";
import { AnswersPage } from "./AnswersPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AnswersPage />
    </QueryClientProvider>,
  );
}

describe("回答记录页", () => {
  it("默认看差评：问题、提问人、留言和引用的文档", async () => {
    renderPage();

    const item = (await screen.findByText("临期商品打几折？")).closest("li")!;
    expect(within(item).getByText(/店员小王/)).toBeInTheDocument();
    expect(within(item).getByText("门店规定是五折")).toBeInTheDocument();
    expect(within(item).getByText(/临期与报损管理\.md/)).toBeInTheDocument();
    expect(screen.queryByText("门店几点打烊？")).not.toBeInTheDocument();
  });

  it("切换到答不上来、全部", async () => {
    renderPage();
    await screen.findByText("临期商品打几折？");

    fireEvent.click(screen.getByRole("button", { name: "答不上来" }));
    expect(await screen.findByText("门店几点打烊？")).toBeInTheDocument();
    expect(screen.queryByText("临期商品打几折？")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "答不上来" })).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(screen.getByRole("button", { name: "全部" }));
    expect(await screen.findByText("临期商品打几折？")).toBeInTheDocument();
    expect(screen.getByText("门店几点打烊？")).toBeInTheDocument();
  });

  it("把当前列表导出为评测题草稿，等待人工标注", async () => {
    let exported: Blob | undefined;
    vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => {
      exported = blob as Blob;
      return "blob:eval";
    });
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    renderPage();
    await screen.findByText("临期商品打几折？");

    fireEvent.click(screen.getByRole("button", { name: "导出为评测题草稿" }));

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
