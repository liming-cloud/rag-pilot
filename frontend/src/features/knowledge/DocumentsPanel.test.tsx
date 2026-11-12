import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { signInAs } from "@/mocks/identity";
import { server } from "@/mocks/server";
import { DocumentsPanel } from "./DocumentsPanel";

function renderPanel(path = "/admin/knowledge/1") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <DocumentsPanel knowledgeBaseId={1} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** 选文件夹：浏览器给出文件夹里的全部文件，带相对路径。 */
async function chooseFolder(...files: File[]) {
  fireEvent.change(await screen.findByLabelText("选择文件夹"), { target: { files } });
}

const batch = () => screen.findByRole("region", { name: "本批上传" });

/** 上传按钮要等读到权限后才显示。 */
async function choose(...files: File[]) {
  fireEvent.change(await screen.findByLabelText("选择文件"), { target: { files } });
}

const table = () => screen.findByRole("table", { name: "文档列表" });
const row = async (filename: string) => (await within(await table()).findByText(filename)).closest("tr")!;

describe("知识库的文档", () => {
  beforeEach(() => signInAs("admin"));

  it("分页列出文档、状态、分块数、大小和失败原因；按状态筛选", async () => {
    renderPanel();

    const ready = await row("临期与报损管理.md");
    expect(within(ready).getByText("可用")).toBeInTheDocument();
    expect(within(ready).getByText("12")).toBeInTheDocument();
    expect(within(ready).getByText("18 KB")).toBeInTheDocument();
    expect(within(await row("旧版陈列手册.txt")).getByText(/不是 UTF-8 编码/)).toBeInTheDocument();
    expect(screen.getByText("共 37 条")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("状态"), { target: { value: "FAILED" } });
    expect(await screen.findByText("共 2 条")).toBeInTheDocument();
  });

  it("处理失败的文档（#145 A9）：模型暂时不可用的可以重新处理、说明何时自动重试；文件本身有问题的提示改好后重传", async () => {
    renderPanel();

    const broken = await row("旧版陈列手册.txt");
    expect(within(broken).getByText("改好文件后重新上传，会替换这条失败记录。")).toBeInTheDocument();
    expect(within(broken).queryByRole("button", { name: /重新处理/ })).not.toBeInTheDocument();

    const modelDown = await row("夜班交接手册.docx");
    expect(within(modelDown).getByText(/会自动重试一次/)).toBeInTheDocument();
    fireEvent.click(within(modelDown).getByRole("button", { name: "重新处理 夜班交接手册.docx" }));

    // 假接口在下一次读列表时就处理完，这里只看最后的结果。
    await vi.waitFor(async () => expect(within(await row("夜班交接手册.docx")).getByText("可用")).toBeInTheDocument(), {
      timeout: 5000,
    });
    expect(within(await row("夜班交接手册.docx")).queryByRole("button", { name: /重新处理/ })).not.toBeInTheDocument();
  });

  it("上传后先显示解析中，后台处理完自动变成可用", async () => {
    renderPanel();
    await table();

    await choose(new File(["# 陈列\n\n新品放在视线高度。"], "陈列规范.md", { type: "text/markdown" }));

    const uploaded = await row("陈列规范.md");
    expect(within(uploaded).getByText("解析中")).toBeInTheDocument();
    expect(await within(await row("陈列规范.md")).findByText("可用", {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it("不支持的格式不上传，其余文件照常上传，结束后汇总", async () => {
    renderPanel();
    await table();

    await choose(
      new File(["PK"], "培训课件.ppt", { type: "application/vnd.ms-powerpoint" }),
      new File(["退货凭小票办理。"], "退换货.txt", { type: "text/plain" }),
    );

    expect(await row("退换货.txt")).toBeInTheDocument();
    const summary = await batch();
    expect(await within(summary).findByText("本批 2 个文件已处理完")).toBeInTheDocument();
    expect(within(summary).getByText("成功 1")).toBeInTheDocument();
    expect(within(summary).getByText("不支持 1")).toBeInTheDocument();
    expect(within(summary).getByText("培训课件.ppt")).toBeInTheDocument();
  });

  it("选文件夹：汇总成功、重复、不支持、失败；只重试失败的", async () => {
    let failOnce = true;
    server.use(
      http.post("/api/knowledge-bases/:id/documents", async ({ request }) => {
        const body = await request.clone().text();
        if (body.includes('filename="交接班.md"') && failOnce) {
          failOnce = false;
          return HttpResponse.json(
            { status: 503, code: "AI_UNAVAILABLE", detail: "模型服务暂不可用，请稍后重试" },
            { status: 503 },
          );
        }
        return undefined;
      }),
    );
    renderPanel();
    await table();

    await chooseFolder(
      new File(["新"], "交接班.md"),
      new File(["重复"], "临期与报损管理.md"),
      new File(["图"], "照片.jpg"),
      new File(["新"], "盘点.txt"),
    );

    const summary = await batch();
    expect(await within(summary).findByText("本批 4 个文件已处理完")).toBeInTheDocument();
    expect(within(summary).getByText("成功 1")).toBeInTheDocument();
    expect(within(summary).getByText("重复 1")).toBeInTheDocument();
    expect(within(summary).getByText("不支持 1")).toBeInTheDocument();
    expect(within(summary).getByText("失败 1")).toBeInTheDocument();
    expect(within(summary).getByRole("alert")).toHaveTextContent("交接班.md：模型服务暂不可用");

    fireEvent.click(within(summary).getByRole("button", { name: "重试失败的 1 个" }));

    expect(await within(summary).findByText("成功 2")).toBeInTheDocument();
    expect(within(summary).getByText("失败 0")).toBeInTheDocument();
    // 重复和不支持的不重试，仍然列出原因。
    expect(within(summary).getByText("重复 1")).toBeInTheDocument();
    expect(await row("交接班.md")).toBeInTheDocument();
  });

  it("一次超过 200 个文件时整批不传，提示分批", async () => {
    renderPanel();
    await table();

    await chooseFolder(...Array.from({ length: 201 }, (_, index) => new File(["x"], `第${index}份.md`)));

    expect(await screen.findByRole("alert")).toHaveTextContent("一次最多上传 200 个文件，这次选了 201 个");
    expect(screen.queryByRole("region", { name: "本批上传" })).not.toBeInTheDocument();
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

  it("上传新版本：在弹窗里选文件，先标出替换哪份，处理完后旧版本消失", async () => {
    renderPanel();
    const old = await row("临期与报损管理.md");

    fireEvent.click(within(old).getByRole("button", { name: "上传新版本 临期与报损管理.md" }));
    const dialog = await screen.findByRole("dialog", { name: "上传新版本" });
    fireEvent.change(within(dialog).getByLabelText("新版本文件"), {
      target: { files: [new File(["# 临期\n\n剩余三分之一时打五折。"], "临期与报损管理-2026版.md")] },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "上传" }));

    expect(within(await row("临期与报损管理-2026版.md")).getByText("替换《临期与报损管理.md》")).toBeInTheDocument();
    await vi.waitFor(
      async () => expect(within(await table()).queryByText("临期与报损管理.md")).not.toBeInTheDocument(),
      { timeout: 3000 },
    );
  });

  it("上传新版本默认继承旧版本的失效日期", async () => {
    renderPanel();
    const old = await row("会员积分规则（本季度）.docx");
    const expiresOn = within(old).getByText(/^至 /).textContent.slice(2);

    fireEvent.click(within(old).getByRole("button", { name: "上传新版本 会员积分规则（本季度）.docx" }));
    const dialog = await screen.findByRole("dialog", { name: "上传新版本" });
    expect(within(dialog).getByLabelText("失效日期")).toHaveValue(expiresOn);
    fireEvent.change(within(dialog).getByLabelText("新版本文件"), {
      target: { files: [new File(["新规则"], "会员积分规则（下季度）.docx")] },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "上传" }));

    expect(within(await row("会员积分规则（下季度）.docx")).getByText(`至 ${expiresOn}`)).toBeInTheDocument();
  });

  it("显示有效期和状态；按有效期筛选，地址里的 validity 作为初始筛选", async () => {
    renderPanel("/admin/knowledge/1?validity=EXPIRED");

    expect(await screen.findByText("共 1 条")).toBeInTheDocument();
    expect(within(await row("临期与报损管理（旧版）.md")).getByText("已失效")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("有效期"), { target: { value: "EXPIRING" } });
    // 今天到期（当天仍有效）和 18 天后到期的都算即将到期。
    expect(await screen.findByText("共 2 条")).toBeInTheDocument();
    expect(within(await row("双十一活动执行方案.pdf")).getByText("即将到期")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("有效期"), { target: { value: "PENDING" } });
    expect(within(await row("收银交接班制度（下周起执行）.md")).getByText("未生效")).toBeInTheDocument();
  });

  it("修改有效期：失效日期早于生效日期时不能保存；保存后状态随之变化", async () => {
    renderPanel();
    const target = await row("临期与报损管理（旧版）.md");

    fireEvent.click(within(target).getByRole("button", { name: "有效期 临期与报损管理（旧版）.md" }));
    const dialog = await screen.findByRole("dialog", { name: "有效期" });
    fireEvent.change(within(dialog).getByLabelText("生效日期"), { target: { value: "2026-03-01" } });
    fireEvent.change(within(dialog).getByLabelText("失效日期"), { target: { value: "2026-02-01" } });
    expect(within(dialog).getByText("失效日期不能早于生效日期")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "保存" })).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText("失效日期"), { target: { value: "" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "保存" }));

    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const updated = await row("临期与报损管理（旧版）.md");
    expect(await within(updated).findByText("2026-03-01 起")).toBeInTheDocument();
    expect(within(updated).queryByText("已失效")).not.toBeInTheDocument();
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
              effectiveFrom: null,
              expiresOn: null,
              validity: "ACTIVE",
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

  it("没有上传、删除、修改权限时看不到这些按钮", async () => {
    signInAs("zhang");
    renderPanel();
    await row("临期与报损管理.md");

    expect(screen.queryByText("上传文档")).not.toBeInTheDocument();
    expect(screen.queryByText("上传文件夹")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "删除 临期与报损管理.md" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "有效期 临期与报损管理.md" })).not.toBeInTheDocument();
  });
});
