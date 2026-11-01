import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { http, HttpResponse } from "msw";
import { signInAs } from "@/mocks/identity";
import { server } from "@/mocks/server";
import { RETAIL } from "@/mocks/settings";
import { SettingsPage } from "./SettingsPage";

function renderPage(path = "/admin/settings") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter([{ path: "/admin/settings", element: <SettingsPage /> }], {
    initialEntries: [path],
  });
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const item = async (label: string) => screen.findByRole("listitem", { name: label });
const openTab = async (name: string) => fireEvent.click(await screen.findByRole("tab", { name }));

async function edit(label: string) {
  fireEvent.click(await within(await item(label)).findByRole("button", { name: `修改${label}` }));
  return screen.findByRole("dialog", { name: `修改${label}` });
}

const branding = async () => (await (await fetch("/api/auth/branding")).json()) as { name: string };
const chatTexts = async () => (await (await fetch("/api/settings/chat")).json()) as { suggestedQuestions: string[] };

describe("系统设置页", () => {
  beforeEach(() => signInAs("admin"));
  afterEach(() => {
    window.history.pushState({}, "", "/");
    sessionStorage.removeItem("mock.brand");
  });

  it("分三个标签，标签记在地址里；每项标出当前值来自哪里", async () => {
    const router = renderPage();

    const name = await item("产品名称");
    expect(name).toHaveTextContent("明答");
    expect(within(name).getByText("内核默认")).toBeInTheDocument();

    await openTab("问答页");
    expect(router.state.location.search).toBe("?tab=chat");
    expect(within(await item("输入框占位")).getByText("行业包")).toBeInTheDocument();
    const questions = await item("推荐问题");
    expect(within(questions).getByText("界面配置")).toBeInTheDocument();
    expect(
      within(questions)
        .getAllByRole("listitem")
        .map((li) => li.textContent),
    ).toEqual(["临期商品怎么处理？", "便当饭团晚上几点得撤下来？", "收银长短款怎么上报？"]);
  });

  it("部署时用环境变量设了品牌，来源显示环境变量", async () => {
    window.history.pushState({}, "", "/?brand=retail");
    renderPage();

    const name = await item("产品名称");
    expect(name).toHaveTextContent("门店运营知识库");
    expect(within(name).getByText("环境变量")).toBeInTheDocument();
  });

  it("改产品名称后以界面为准，登录页读到的也是新名称", async () => {
    renderPage();
    const dialog = await edit("产品名称");
    const input = within(dialog).getByLabelText("产品名称");
    expect(within(dialog).getByText("默认值（内核默认）：明答")).toBeInTheDocument();

    fireEvent.change(input, { target: { value: "  " } });
    expect(within(dialog).getByRole("button", { name: "保存" })).toBeDisabled();
    expect(within(dialog).getByRole("alert")).toHaveTextContent("不能为空");
    fireEvent.change(input, { target: { value: "很".repeat(31) } });
    expect(within(dialog).getByRole("alert")).toHaveTextContent("最多 30 个字");
    expect(within(dialog).getByText("31/30")).toBeInTheDocument();

    fireEvent.change(input, { target: { value: " 便利店知识库 " } });
    fireEvent.click(within(dialog).getByRole("button", { name: "保存" }));

    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const name = await item("产品名称");
    await vi.waitFor(() => expect(within(name).getByText("界面配置")).toBeInTheDocument());
    expect(name).toHaveTextContent("便利店知识库");
    expect(name).toHaveTextContent("管理员 于");
    expect((await branding()).name).toBe("便利店知识库");
  });

  it("推荐问题可以增删和调整顺序，最多 4 个，不能有空的或重复的", async () => {
    renderPage("/admin/settings?tab=chat");
    const dialog = await edit("推荐问题");

    fireEvent.click(within(dialog).getByRole("button", { name: "添加问题" }));
    const save = within(dialog).getByRole("button", { name: "保存" });
    expect(save).toBeDisabled();
    expect(within(dialog).getByRole("alert")).toHaveTextContent("有空的问题");
    expect(within(dialog).getByRole("button", { name: "最多 4 个" })).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText("问题 4"), { target: { value: "临期商品怎么处理？" } });
    expect(within(dialog).getByRole("alert")).toHaveTextContent("有重复的问题");
    fireEvent.change(within(dialog).getByLabelText("问题 4"), { target: { value: "盘点前要做哪些准备？" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "上移问题 4" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "删除问题 1" }));
    fireEvent.click(save);

    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await vi.waitFor(async () =>
      expect((await chatTexts()).suggestedQuestions).toEqual([
        "便当饭团晚上几点得撤下来？",
        "盘点前要做哪些准备？",
        "收银长短款怎么上报？",
      ]),
    );
  });

  it("恢复默认要先确认，确认后回到行业包的值", async () => {
    renderPage("/admin/settings?tab=chat");
    expect(within(await item("输入框占位")).queryByRole("button", { name: /恢复/ })).not.toBeInTheDocument();

    fireEvent.click(await within(await item("推荐问题")).findByRole("button", { name: "恢复推荐问题的默认值" }));
    const confirm = await screen.findByRole("dialog", { name: "恢复推荐问题的默认值？" });
    expect(confirm).toHaveTextContent("改用行业包的值：临期商品怎么处理？ / 鲜食晚上几点下架？");
    fireEvent.click(within(confirm).getByRole("button", { name: "恢复默认" }));

    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const questions = await item("推荐问题");
    await vi.waitFor(() => expect(within(questions).getByText("行业包")).toBeInTheDocument());
    expect(within(questions).getAllByRole("listitem")).toHaveLength(4);
  });

  it("行业包只读：显示提示词角色、改写提示、时区，词表可以搜索；说明里的例子取自词表", async () => {
    renderPage("/admin/settings?tab=pack");

    const pack = await screen.findByRole("region", { name: "行业包" });
    expect(within(pack).getByRole("note")).toHaveTextContent("当前行业包：新零售（retail）");
    expect(pack).toHaveTextContent("你是连锁门店的运营助手，回答门店员工的问题。");
    expect(pack).toHaveTextContent("Asia/Shanghai");
    expect(pack).toHaveTextContent("例如问“便当”也能找到写着“鲜食”的资料");
    expect(within(pack).queryByRole("button", { name: /修改/ })).not.toBeInTheDocument();

    const table = within(pack).getByRole("table", { name: "行业词表" });
    expect(within(table).getAllByRole("row")).toHaveLength(11);
    fireEvent.change(within(pack).getByLabelText("搜索词表"), { target: { value: "便当" } });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent("鲜食");
    fireEvent.change(within(pack).getByLabelText("搜索词表"), { target: { value: "不存在的词" } });
    expect(table).toHaveTextContent("没有找到包含“不存在的词”的词");
  });

  it("词表超过一页时分页", async () => {
    const vocabulary = Array.from({ length: 25 }, (_, index) => ({
      term: `类别${index + 1}`,
      members: [`词${index + 1}`],
    }));
    server.use(
      http.get("/api/settings", () =>
        HttpResponse.json({ industryPack: { ...RETAIL, vocabulary }, statsZone: "Asia/Shanghai", items: [] }),
      ),
    );
    renderPage("/admin/settings?tab=pack");

    const table = await screen.findByRole("table", { name: "行业词表" });
    expect(within(table).getAllByRole("row")).toHaveLength(11);
    expect(screen.getByText("共 25 条")).toBeInTheDocument();
  });

  it("没有查看权限时不显示设置内容", async () => {
    signInAs("zhang");
    renderPage();

    expect(await screen.findByText("没有权限执行此操作，请联系管理员")).toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  });
});
