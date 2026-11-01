import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { signInAs } from "@/mocks/identity";
import { ModelsPage } from "./ModelsPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ModelsPage />
    </QueryClientProvider>,
  );
}

const usageRow = async (name: string) =>
  (await within(await screen.findByRole("table", { name: "各用途的模型" })).findByText(name)).closest("tr")!;

async function openUsage(name: string) {
  fireEvent.click(within(await usageRow(name)).getByRole("button", { name: `更换 ${name}` }));
  return screen.findByRole("dialog", { name: `更换${name}模型` });
}

describe("模型管理页", () => {
  beforeEach(() => signInAs("admin"));
  afterEach(() => window.history.pushState({}, "", "/"));

  it("列出各用途的服务商、模型和来源；嵌入只读；密钥只显示后 4 位", async () => {
    renderPage();

    const answer = await usageRow("回答");
    expect(within(answer).getByText("DeepSeek 官方")).toBeInTheDocument();
    expect(within(answer).getByText("界面配置")).toBeInTheDocument();
    expect(within(await usageRow("重排")).getByText("环境变量")).toBeInTheDocument();
    const embedding = await usageRow("嵌入");
    expect(within(embedding).getByText("只读")).toBeInTheDocument();
    expect(within(embedding).queryByRole("button")).not.toBeInTheDocument();
    const providers = screen.getByRole("table", { name: "服务商" });
    expect(within(providers).getByText("••••8f2a")).toBeInTheDocument();
    expect(providers).not.toHaveTextContent(/sk-/);
  });

  it("先测通才能保存；改了服务商或模型要重新测；保存后立即显示并记一条变更", async () => {
    renderPage();
    const dialog = await openUsage("重排");
    const save = within(dialog).getByRole("button", { name: "保存" });
    expect(save).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText("服务商"), { target: { value: "1" } });
    fireEvent.change(within(dialog).getByLabelText("模型名称"), { target: { value: "bge-reranker-not-exist" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "测试连通" }));
    expect(await within(dialog).findByText(/模型 bge-reranker-not-exist 不存在/)).toBeInTheDocument();
    expect(save).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText("模型名称"), { target: { value: "bge-reranker-v2" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "测试连通" }));
    expect(await within(dialog).findByText(/连通正常/)).toBeInTheDocument();
    expect(save).toBeEnabled();
    fireEvent.change(within(dialog).getByLabelText("模型名称"), { target: { value: "bge-reranker-v3" } });
    expect(save).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText("模型名称"), { target: { value: "bge-reranker-v2" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "测试连通" }));
    await within(dialog).findByText(/连通正常/);
    fireEvent.click(save);

    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const rerank = await usageRow("重排");
    expect(await within(rerank).findByText("bge-reranker-v2")).toBeInTheDocument();
    expect(within(rerank).getByText("界面配置")).toBeInTheDocument();
    const changes = screen.getByRole("table", { name: "变更记录" });
    expect(await within(changes).findByText("bge-reranker-v2")).toBeInTheDocument();
  });

  it("服务商连不通时提示原因，不能保存", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "添加服务商" }));
    const form = await screen.findByRole("form", { name: "添加服务商" });
    fireEvent.change(within(form).getByLabelText("名称"), { target: { value: "坏掉的服务商" } });
    fireEvent.change(within(form).getByLabelText("地址"), { target: { value: "http://unreachable.local/v1" } });
    fireEvent.change(within(form).getByLabelText("密钥"), { target: { value: "sk-test-1234abcd" } });
    fireEvent.click(within(form).getByRole("button", { name: "保存" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(await within(screen.getByRole("table", { name: "服务商" })).findByText("••••abcd")).toBeInTheDocument();

    const dialog = await openUsage("轻量任务");
    fireEvent.change(within(dialog).getByLabelText("服务商"), {
      target: { value: within(dialog).getByRole("option", { name: "坏掉的服务商" }).getAttribute("value") },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "测试连通" }));
    expect(await within(dialog).findByText(/连接超时/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "保存" })).toBeDisabled();
  });

  it("在用的服务商不能删除；不在用的可以", async () => {
    renderPage();
    const providers = await screen.findByRole("table", { name: "服务商" });
    expect(await within(providers).findByRole("button", { name: "删除 DeepSeek 官方" })).toBeDisabled();

    fireEvent.click(within(providers).getByRole("button", { name: "删除 门店机房 vLLM" }));
    await vi.waitFor(() => expect(within(providers).queryByText("门店机房 vLLM")).not.toBeInTheDocument());
  });

  it("从变更记录恢复，该用途换回记录里的模型并新增一条“恢复”记录", async () => {
    renderPage();
    const dialog = await openUsage("回答");
    fireEvent.change(within(dialog).getByLabelText("模型名称"), { target: { value: "deepseek-reasoner" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "测试连通" }));
    await within(dialog).findByText(/连通正常/);
    fireEvent.click(within(dialog).getByRole("button", { name: "保存" }));
    expect(await within(await usageRow("回答")).findByText("deepseek-reasoner")).toBeInTheDocument();

    const changes = screen.getByRole("table", { name: "变更记录" });
    fireEvent.click(await within(changes).findByRole("button", { name: "恢复回答为 deepseek-chat" }));

    expect(await within(await usageRow("回答")).findByText("deepseek-chat")).toBeInTheDocument();
    expect(await within(changes).findByText("恢复操作")).toBeInTheDocument();
  });

  it("没设主密钥时提示原因，添加服务商时不能填密钥", async () => {
    window.history.pushState({}, "", "/?masterKey=missing");
    renderPage();

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
