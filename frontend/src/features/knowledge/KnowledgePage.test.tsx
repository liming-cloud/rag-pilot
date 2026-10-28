import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/mocks/server";
import { KnowledgePage } from "./KnowledgePage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <KnowledgePage />
    </QueryClientProvider>,
  );
}

function choose(...files: File[]) {
  fireEvent.change(screen.getByLabelText("选择文件"), { target: { files } });
}

describe("知识库页", () => {
  it("列出知识库，默认显示第一个库的文档和失败原因", async () => {
    renderPage();

    expect(await screen.findByRole("button", { name: "门店运营知识库" })).toHaveAttribute("aria-current", "true");
    const documents = await screen.findByRole("list", { name: "文档" });
    expect(within(documents).getByText("临期与报损管理.md")).toBeInTheDocument();
    expect(within(documents).getByText("可用")).toBeInTheDocument();
    expect(within(documents).getByText(/不是 UTF-8 编码/)).toBeInTheDocument();
  });

  it("上传后文档出现在列表里", async () => {
    renderPage();
    await screen.findByRole("list", { name: "文档" });

    choose(new File(["# 收银\n\n交班清点备用金。"], "收银规范.md", { type: "text/markdown" }));

    const documents = screen.getByRole("list", { name: "文档" });
    expect(await within(documents).findByText("收银规范.md")).toBeInTheDocument();
  });

  it("上传后先显示解析中，后台处理完自动变成可用", async () => {
    renderPage();
    await screen.findByRole("list", { name: "文档" });

    choose(new File(["# 陈列\n\n新品放在视线高度。"], "陈列规范.md", { type: "text/markdown" }));

    const row = (await screen.findByText("陈列规范.md")).closest("li")!;
    expect(within(row).getByText("解析中")).toBeInTheDocument();
    expect(await within(row).findByText("可用", {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it("后端拒收时显示原因，其余文件照常上传", async () => {
    renderPage();
    await screen.findByRole("list", { name: "文档" });

    choose(
      new File(["PK"], "门店培训.pptx", { type: "application/vnd.ms-powerpoint" }),
      new File(["退货凭小票办理。"], "退换货.txt", { type: "text/plain" }),
    );

    expect(await screen.findByText(/门店培训\.pptx：目前支持 PDF、Word/)).toBeInTheDocument();
    expect(await within(screen.getByRole("list", { name: "文档" })).findByText("退换货.txt")).toBeInTheDocument();
  });

  it("删除文档要先确认，确认后从列表消失", async () => {
    renderPage();
    const documents = await screen.findByRole("list", { name: "文档" });

    fireEvent.click(within(documents).getByRole("button", { name: "删除 临期与报损管理.md" }));
    expect(within(documents).getByText("临期与报损管理.md")).toBeInTheDocument();
    fireEvent.click(within(documents).getByRole("button", { name: "确认删除" }));

    await vi.waitFor(() => expect(within(documents).queryByText("临期与报损管理.md")).not.toBeInTheDocument());
  });

  it("上传新版本：先标出替换哪份，处理完后旧版本消失", async () => {
    renderPage();
    const documents = await screen.findByRole("list", { name: "文档" });

    fireEvent.change(within(documents).getByLabelText("上传新版本 临期与报损管理.md"), {
      target: { files: [new File(["# 临期\n\n剩余三分之一时打五折。"], "临期与报损管理-2026版.md")] },
    });

    const row = (await within(documents).findByText("临期与报损管理-2026版.md")).closest("li")!;
    expect(within(row).getByText("替换《临期与报损管理.md》")).toBeInTheDocument();
    await vi.waitFor(() => expect(within(documents).queryByText("临期与报损管理.md")).not.toBeInTheDocument(), {
      timeout: 3000,
    });
    expect(within(row).getByText("可用")).toBeInTheDocument();
  });

  it("新版本解析失败时提示旧版本已保留", async () => {
    server.use(
      http.get("/api/knowledge-bases/:id/documents", () =>
        HttpResponse.json([
          {
            id: 21,
            filename: "收银规范-新版.txt",
            status: "FAILED",
            error: "文件不是 UTF-8 编码",
            chunkCount: 0,
            sizeBytes: 1024,
            createdAt: null,
            replacesDocumentId: 20,
          },
          {
            id: 20,
            filename: "收银规范.txt",
            status: "READY",
            error: null,
            chunkCount: 3,
            sizeBytes: 2048,
            createdAt: null,
            replacesDocumentId: null,
          },
        ]),
      ),
    );
    renderPage();

    const row = (await screen.findByText("收银规范-新版.txt")).closest("li")!;
    expect(within(row).getByText(/旧版本《收银规范\.txt》已保留/)).toBeInTheDocument();
  });

  it("新建知识库后选中它", async () => {
    renderPage();
    await screen.findByRole("button", { name: "门店运营知识库" });

    fireEvent.change(screen.getByLabelText("新知识库名称"), { target: { value: "促销活动" } });
    fireEvent.click(screen.getByRole("button", { name: "新建" }));

    expect(await screen.findByRole("button", { name: "促销活动" })).toHaveAttribute("aria-current", "true");
    expect(await screen.findByText("还没有文档，先上传一份吧。")).toBeInTheDocument();
  });

  it("接口失败时给出中文提示", async () => {
    server.use(http.get("/api/knowledge-bases", () => new HttpResponse(null, { status: 500 })));
    renderPage();
    expect(await screen.findByText("读取知识库失败，请稍后重试。")).toBeInTheDocument();
  });
});
