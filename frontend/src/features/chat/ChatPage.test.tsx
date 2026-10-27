import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http } from "msw";
import { server } from "@/mocks/server";
import { sseResponse } from "@/mocks/sse";
import { sampleAnswer } from "@/mocks/handlers";
import { ChatPage } from "./ChatPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ChatPage />
    </QueryClientProvider>,
  );
}

function ask(question: string) {
  fireEvent.change(screen.getByLabelText("输入问题"), { target: { value: question } });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
}

describe("问答页", () => {
  it("显示带引用编号的回答和来源列表", async () => {
    renderPage();
    ask("临期商品怎么处理？");

    expect(await screen.findByText("临期商品怎么处理？")).toBeInTheDocument();
    const answer = await screen.findByRole("article", { name: "回答" });
    expect(within(answer).getByRole("button", { name: "查看引用 1" })).toBeInTheDocument();
    expect(within(answer).getByRole("list", { name: "来源" })).toHaveTextContent("临期与报损管理.md");
  });

  it("点引用编号展开原文片段", async () => {
    renderPage();
    ask("临期商品怎么处理？");

    fireEvent.click(await screen.findByRole("button", { name: "查看引用 1" }));
    const excerpt = screen.getByRole("region", { name: "引用原文" });
    expect(excerpt).toHaveTextContent("临期与报损管理.md");
    expect(excerpt).toHaveTextContent("临期商品 > 下架时限");
    expect(excerpt).toHaveTextContent("保质期剩余");
  });

  it("边生成边显示文字，结束后换成带引用的回答", async () => {
    let finish = () => {};
    const finished = new Promise<void>((resolve) => (finish = resolve));
    server.use(
      http.post("/api/ask/stream", () =>
        sseResponse(
          [
            ["delta", { text: "保质期剩余三分之一时" }],
            ["done", sampleAnswer],
          ],
          finished,
        ),
      ),
    );
    renderPage();
    ask("临期商品怎么处理？");

    expect(await screen.findByText("保质期剩余三分之一时")).toBeInTheDocument();
    expect(screen.queryByRole("article", { name: "回答" })).not.toBeInTheDocument();
    finish();
    expect(await screen.findByRole("article", { name: "回答" })).toBeInTheDocument();
  });

  it("拒答时用最终结果替换已显示的文字", async () => {
    server.use(
      http.post("/api/ask/stream", () =>
        sseResponse([
          ["delta", { text: "这段文字没有引用" }],
          [
            "done",
            {
              text: "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。",
              refused: true,
              refusalReason: "UNCITED",
              citations: [],
              topRelevance: 0.8,
            },
          ],
        ]),
      ),
    );
    renderPage();
    ask("明天会下雨吗？");

    expect(await screen.findByText("没有找到答案")).toBeInTheDocument();
    expect(screen.getByText(/建议换个问法/)).toBeInTheDocument();
    expect(screen.queryByText("这段文字没有引用")).not.toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "来源" })).not.toBeInTheDocument();
  });

  it("服务端报错时给出中文提示", async () => {
    server.use(
      http.post("/api/ask/stream", () =>
        sseResponse([["error", { status: 503, detail: "对话模型服务暂时不可用" }]]),
      ),
    );
    renderPage();
    ask("临期商品怎么处理？");

    expect(await screen.findByText("暂时无法回答，请稍后重试。")).toBeInTheDocument();
  });

  it("可以点赞或点踩", async () => {
    renderPage();
    ask("临期商品怎么处理？");

    const helpful = await screen.findByRole("button", { name: "有帮助" });
    fireEvent.click(helpful);
    expect(helpful).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(screen.getByRole("button", { name: "没帮助" }));
    expect(helpful).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "没帮助" })).toHaveAttribute("aria-pressed", "true");
  });
});
