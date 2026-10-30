import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { signInAs } from "@/mocks/identity";
import { server } from "@/mocks/server";
import { DocumentsPanel } from "./DocumentsPanel";

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <DocumentsPanel knowledgeBaseId={1} />
    </QueryClientProvider>,
  );
}

/** 上传按钮要等读到权限后才显示。 */
async function choose(...files: File[]) {
  fireEvent.change(await screen.findByLabelText("选择文件"), { target: { files } });
}

const table = () => screen.findByRole("table", { name: "文档列表" });
const row = async (filename: string) => (await within(await table()).findByText(filename)).closest("tr")!;

describe("知识库的文档", () => {
  beforeEach(() => signInAs("admin"));

  it("分页列出文档、状态和失败原因；按状态筛选", async () => {
    renderPanel();

    expect(within(await row("临期与报损管理.md")).getByText("可用")).toBeInTheDocument();
    expect(within(await row("旧版陈列手册.txt")).getByText(/不是 UTF-8 编码/)).toBeInTheDocument();
    expect(screen.getByText("共 32 条 · 第 1 / 2 页")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("状态"), { target: { value: "FAILED" } });
    expect(await screen.findByText("共 1 条 · 第 1 / 1 页")).toBeInTheDocument();
  });

  it("上传后先显示解析中，后台处理完自动变成可用", async () => {
    renderPanel();
    await table();

    await choose(new File(["# 陈列\n\n新品放在视线高度。"], "陈列规范.md", { type: "text/markdown" }));

    const uploaded = await row("陈列规范.md");
    expect(within(uploaded).getByText("解析中")).toBeInTheDocument();
    expect(await within(await row("陈列规范.md")).findByText("可用", {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it("后端拒收时显示原因，其余文件照常上传", async () => {
    renderPanel();
    await table();

    await choose(
      new File(["PK"], "门店培训.pptx", { type: "application/vnd.ms-powerpoint" }),
      new File(["退货凭小票办理。"], "退换货.txt", { type: "text/plain" }),
    );

    expect(await screen.findByText(/门店培训\.pptx：目前支持 PDF、Word/)).toBeInTheDocument();
    expect(await row("退换货.txt")).toBeInTheDocument();
  });

  it("删除文档要先确认，确认后从列表消失", async () => {
    renderPanel();
    const target = await row("临期与报损管理.md");

    fireEvent.click(within(target).getByRole("button", { name: "删除 临期与报损管理.md" }));
    fireEvent.click(within(target).getByRole("button", { name: "确认删除" }));

    await vi.waitFor(async () =>
      expect(within(await table()).queryByText("临期与报损管理.md")).not.toBeInTheDocument(),
    );
  });

  it("上传新版本：先标出替换哪份，处理完后旧版本消失", async () => {
    renderPanel();
    const old = await row("临期与报损管理.md");

    fireEvent.change(within(old).getByLabelText("上传新版本 临期与报损管理.md"), {
      target: { files: [new File(["# 临期\n\n剩余三分之一时打五折。"], "临期与报损管理-2026版.md")] },
    });

    expect(within(await row("临期与报损管理-2026版.md")).getByText("替换《临期与报损管理.md》")).toBeInTheDocument();
    await vi.waitFor(
      async () => expect(within(await table()).queryByText("临期与报损管理.md")).not.toBeInTheDocument(),
      { timeout: 3000 },
    );
  });

  it("新版本解析失败时提示旧版本已保留", async () => {
    server.use(
      http.get("/api/knowledge-bases/:id/documents", () =>
        HttpResponse.json({
          items: [
            {
              id: 21,
              filename: "收银规范-新版.txt",
              status: "FAILED",
              error: "文件不是 UTF-8 编码",
              chunkCount: 0,
              sizeBytes: 1024,
              createdAt: null,
              replacesDocumentId: 20,
              replacesFilename: "收银规范.txt",
            },
          ],
          total: 1,
          page: 1,
          size: 20,
        }),
      ),
    );
    renderPanel();

    expect(within(await row("收银规范-新版.txt")).getByText(/旧版本《收银规范\.txt》已保留/)).toBeInTheDocument();
  });

  it("没有上传、删除权限时看不到这些按钮", async () => {
    signInAs("zhang");
    renderPanel();
    await row("临期与报损管理.md");

    expect(screen.queryByText("上传文档")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "删除 临期与报损管理.md" })).not.toBeInTheDocument();
  });
});
