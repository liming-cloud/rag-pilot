import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { signInAs } from "@/mocks/identity";
import { ModelsPage } from "./ModelsPage";

function renderPage(path = "/admin/models?tab=usages") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter([{ path: "/admin/models", element: <ModelsPage /> }], { initialEntries: [path] });
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const usageRow = async (name: string) =>
  (await within(await screen.findByRole("table", { name: "各用途的模型" })).findByText(name)).closest("tr")!;

async function openUsage(name: string) {
  fireEvent.click(within(await usageRow(name)).getByRole("button", { name: `更换 ${name}` }));
  return screen.findByRole("dialog", { name: `更换${name}模型` });
}

const openTab = async (name: string) => fireEvent.click(await screen.findByRole("tab", { name }));

describe("模型管理页", () => {
  beforeEach(() => signInAs("admin"));
  afterEach(() => window.history.pushState({}, "", "/"));

  it("分三个标签，服务商在前、默认打开；标签记在地址里；嵌入只读", async () => {
    const router = renderPage("/admin/models");

    expect((await screen.findAllByRole("tab")).map((tab) => tab.textContent)).toEqual([
      "服务商",
      "用途与模型",
      "变更记录",
    ]);
    expect(screen.getByRole("tab", { name: "服务商" })).toHaveAttribute("aria-selected", "true");
    expect(await within(screen.getByRole("table", { name: "服务商" })).findByText("••••8f2a")).toBeInTheDocument();

    await openTab("用途与模型");
    expect(router.state.location.search).toBe("?tab=usages");
    const answer = await usageRow("回答");
    expect(within(answer).getByText("DeepSeek 官方")).toBeInTheDocument();
    expect(within(answer).getByText("界面配置")).toBeInTheDocument();
    expect(within(await usageRow("备用回答")).getByText("环境变量")).toBeInTheDocument();
    const embedding = await usageRow("嵌入");
    expect(within(embedding).getByText("只读")).toBeInTheDocument();
    expect(within(embedding).queryByRole("button")).not.toBeInTheDocument();

    await openTab("服务商");
    expect(router.state.location.search).toBe("");
  });

  it("带组织前缀的模型名：名称和前缀分两行，悬停看全名", async () => {
    renderPage();
    const hedge = await usageRow("备用回答");
    const name = within(hedge).getByTitle("deepseek-ai/DeepSeek-V4.1-Flash");
    expect(within(name).getByText("DeepSeek-V4.1-Flash")).toBeInTheDocument();
    expect(within(name).getByText("deepseek-ai")).toBeInTheDocument();
  });

  it("从服务商的模型列表里选，测试并保存一步完成，保存后显示并记一条变更", async () => {
    renderPage();
    const dialog = await openUsage("轻量任务");

    const list = await within(dialog).findByRole("list", { name: "可选的模型" });
    expect(within(list).getAllByRole("button").length).toBeGreaterThan(5);
    expect(within(list).getByRole("button", { pressed: true })).toHaveTextContent("Qwen2.5-7B-Instruct");
    fireEvent.change(within(dialog).getByLabelText("模型名称"), { target: { value: "qwen3" } });
    expect(within(list).getAllByRole("button")).toHaveLength(2);
    fireEvent.click(within(list).getByTitle("Qwen/Qwen3-32B").closest("button")!);
    expect(within(dialog).getByLabelText("模型名称")).toHaveValue("Qwen/Qwen3-32B");
    fireEvent.click(within(dialog).getByRole("button", { name: "测试并保存" }));

    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const light = await usageRow("轻量任务");
    expect(await within(light).findByTitle("Qwen/Qwen3-32B")).toBeInTheDocument();
    expect(within(light).getByText("界面配置")).toBeInTheDocument();

    await openTab("变更记录");
    expect(
      await within(await screen.findByRole("table", { name: "变更记录" })).findByTitle("Qwen/Qwen3-32B"),
    ).toBeInTheDocument();
  });

  it("测不通时不保存，弹窗里说明原因；也可以只测试", async () => {
    renderPage();
    const dialog = await openUsage("重排");

    fireEvent.change(within(dialog).getByLabelText("模型名称"), { target: { value: "bge-reranker-not-exist" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "测试并保存" }));
    expect(await within(dialog).findByText(/没有保存：服务商返回 404/)).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText("模型名称"), { target: { value: "BAAI/bge-reranker-v2-m3" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "只测试" }));
    expect(await within(dialog).findByText(/连通正常/)).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("button", { name: "取消" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(within(await usageRow("重排")).getByText("环境变量")).toBeInTheDocument();
  });

  it("服务商不提供模型列表时提示手动填写", async () => {
    renderPage();
    const dialog = await openUsage("重排");
    fireEvent.change(within(dialog).getByLabelText("服务商"), { target: { value: "2" } });
    expect(await within(dialog).findByText(/没有提供模型列表，请手动填写/)).toBeInTheDocument();
  });

  it("添加服务商后密钥只显示后 4 位；在用的服务商不能删除，不在用的确认后可以删", async () => {
    renderPage("/admin/models?tab=providers");
    fireEvent.click(await screen.findByRole("button", { name: "添加服务商" }));
    const form = await screen.findByRole("form", { name: "添加服务商" });
    fireEvent.change(within(form).getByLabelText("名称"), { target: { value: "新服务商" } });
    fireEvent.change(within(form).getByLabelText("地址"), { target: { value: "https://llm.example.com/v1" } });
    fireEvent.change(within(form).getByLabelText("密钥"), { target: { value: "sk-test-1234abcd" } });
    fireEvent.click(within(form).getByRole("button", { name: "保存" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    const providers = screen.getByRole("table", { name: "服务商" });
    expect(await within(providers).findByText("••••abcd")).toBeInTheDocument();
    expect(providers).not.toHaveTextContent("sk-test");
    expect(within(providers).getByRole("button", { name: "删除 DeepSeek 官方" })).toBeDisabled();
    fireEvent.click(within(providers).getByRole("button", { name: "删除 门店机房 vLLM" }));
    const confirm = await screen.findByRole("dialog", { name: "删除服务商“门店机房 vLLM”？" });
    fireEvent.click(within(confirm).getByRole("button", { name: "取消" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(within(providers).getByText("门店机房 vLLM")).toBeInTheDocument();

    fireEvent.click(within(providers).getByRole("button", { name: "删除 门店机房 vLLM" }));
    fireEvent.click(
      within(await screen.findByRole("dialog", { name: "删除服务商“门店机房 vLLM”？" })).getByRole("button", {
        name: "删除",
      }),
    );
    await vi.waitFor(() => expect(within(providers).queryByText("门店机房 vLLM")).not.toBeInTheDocument());
  });

  it("恢复前确认，确认后该用途换回记录里的模型，并新增一条“恢复操作”", async () => {
    renderPage();
    const dialog = await openUsage("回答");
    fireEvent.change(within(dialog).getByLabelText("模型名称"), { target: { value: "deepseek-reasoner" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "测试并保存" }));
    expect(await within(await usageRow("回答")).findByTitle("deepseek-reasoner")).toBeInTheDocument();

    await openTab("变更记录");
    const changes = await screen.findByRole("table", { name: "变更记录" });
    fireEvent.click(await within(changes).findByRole("button", { name: "恢复回答为 deepseek-chat" }));
    const confirm = await screen.findByRole("dialog", { name: "恢复回答模型" });
    expect(confirm).toHaveTextContent("DeepSeek 官方 / deepseek-chat");
    fireEvent.click(within(confirm).getByRole("button", { name: "确认恢复" }));

    expect(await within(changes).findByText("恢复操作")).toBeInTheDocument();
    await openTab("用途与模型");
    expect(await within(await usageRow("回答")).findByTitle("deepseek-chat")).toBeInTheDocument();
  });

  it("没设主密钥时提示原因，添加服务商时不能填密钥", async () => {
    window.history.pushState({}, "", "/?masterKey=missing");
    renderPage("/admin/models?tab=providers");

    expect(await screen.findByText(/没有设置主密钥 RAG_MASTER_KEY/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "添加服务商" }));
    const form = await screen.findByRole("form", { name: "添加服务商" });
    expect(within(form).getByLabelText("密钥")).toBeDisabled();
  });

  it("没有 model:view 的账号读不到模型配置", async () => {
    signInAs("zhang");
    renderPage();
    expect(await screen.findByText(/没有权限执行此操作/)).toBeInTheDocument();
  });
});
