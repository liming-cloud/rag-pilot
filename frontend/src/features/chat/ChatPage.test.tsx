import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { streamTiming } from "@/mocks/conversations";
import { server } from "@/mocks/server";
import { sseResponse } from "@/mocks/sse";
import { ChatPage } from "./ChatPage";

/** 示例数据见 mocks/conversations.ts：店员小王只能访问“门店运营知识库”；会话 902 选过的“商品知识库”已收回授权。 */
function renderPage(path = "/chat") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: "/chat", element: <ChatPage /> },
      { path: "/chat/:conversationId", element: <ChatPage /> },
    ],
    { initialEntries: [path] },
  );
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

function ask(question: string) {
  fireEvent.change(screen.getByLabelText("输入问题"), { target: { value: question } });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
}

/**
 * 旁听发出的请求、照常由假接口应答：假接口里的会话会真的更新，停止接口也会真的让生成停下来。
 * 请求体是异步读出的，断言要放在 vi.waitFor 里。
 */
function spyRequests(method: string, path: RegExp) {
  const seen: Array<{ path: string; body: unknown }> = [];
  const listener = ({ request }: { request: Request }) => {
    const pathname = new URL(request.url).pathname;
    if (request.method === method && path.test(pathname)) {
      void request
        .clone()
        .json()
        .catch(() => null)
        .then((body: unknown) => seen.push({ path: pathname, body }));
    }
  };
  server.events.on("request:start", listener);
  onTestFinished(() => server.events.removeListener("request:start", listener));
  return seen;
}

/** 等这一轮生成完、出现正式的回答卡片后，返回它所在的区域。 */
async function finishedTurn(name: string) {
  return vi.waitFor(
    () => {
      const region = screen.getByRole("region", { name });
      within(region).getByRole("article", { name: "回答" });
      return region;
    },
    { timeout: 3000 },
  );
}

const saved = { ...streamTiming };
beforeEach(() => Object.assign(streamTiming, { stepMillis: 0, deltaMillis: 0 }));
afterEach(() => Object.assign(streamTiming, saved));

describe("会话侧栏", () => {
  it("按最后一次提问的日期分组列出自己的会话", async () => {
    renderPage();

    const list = await screen.findByRole("navigation", { name: "会话列表" });
    expect(await within(list).findByRole("region", { name: "今天" })).toHaveTextContent("临期商品处理");
    expect(within(list).getByRole("region", { name: "昨天" })).toHaveTextContent("冷柜与冷冻柜温度");
    expect(within(list).getByRole("region", { name: "前 7 天" })).toHaveTextContent("交班差额处理");
    expect(within(list).getByRole("region", { name: "前 30 天" })).toHaveTextContent("员工年假怎么请");
  });

  it("重命名会话", async () => {
    const requests = spyRequests("PATCH", /^\/api\/conversations\/\d+$/);
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "重命名“临期商品处理”" }));
    fireEvent.change(screen.getByLabelText("会话标题"), { target: { value: "临期与报损" } });
    fireEvent.click(screen.getByRole("button", { name: "保存标题" }));

    await vi.waitFor(() =>
      expect(requests).toEqual([{ path: "/api/conversations/901", body: { title: "临期与报损" } }]),
    );
    expect(await screen.findByRole("link", { name: "临期与报损" })).toBeInTheDocument();
  });

  it("确认后删除会话，正在看的会话被删后回到新对话", async () => {
    const router = renderPage("/chat/901");

    fireEvent.click(await screen.findByRole("button", { name: "删除“临期商品处理”" }));
    fireEvent.click(
      within(screen.getByRole("group", { name: "删除“临期商品处理”" })).getByRole("button", { name: "删除" }),
    );

    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/chat"));
    const list = screen.getByRole("navigation", { name: "会话列表" });
    await vi.waitFor(() => expect(list).not.toHaveTextContent("临期商品处理"));
  });
});

describe("知识库选择", () => {
  it("新对话只列已授权的库并默认全选", async () => {
    renderPage();

    const picker = await screen.findByRole("group", { name: "知识库" });
    expect(await within(picker).findByRole("button", { name: /门店运营知识库/ })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(within(picker).queryByRole("button", { name: /商品知识库/ })).not.toBeInTheDocument();
  });

  it("授权收回的库标“已无权限”、不能再选，引用原文显示已无权查看", async () => {
    renderPage("/chat/902");

    const picker = await screen.findByRole("group", { name: "知识库" });
    const revoked = await within(picker).findByRole("button", { name: /商品知识库/ });
    expect(revoked).toHaveTextContent("已无权限");
    expect(revoked).toBeDisabled();

    const followUp = await screen.findByRole("region", { name: "问答：那冷冻柜呢？" });
    fireEvent.click(within(followUp).getAllByRole("button", { name: "查看引用 1" })[0]!);
    expect(within(followUp).getByRole("region", { name: "引用原文" })).toHaveTextContent("已无权查看原文");
  });
});

describe("提问", () => {
  it("新对话第一次提问时建会话，回答后侧栏出现生成的标题", async () => {
    const router = renderPage();
    await screen.findByRole("button", { name: /门店运营知识库/ });
    ask("临期商品怎么处理？");

    const answer = await screen.findByRole("article", { name: "回答" });
    expect(within(answer).getByRole("list", { name: "来源" })).toHaveTextContent("临期与报损管理.md");
    expect(router.state.location.pathname).toMatch(/^\/chat\/\d+$/);
    expect(
      await within(screen.getByRole("region", { name: "今天" })).findByText("临期商品怎么处理"),
    ).toBeInTheDocument();
  });

  it("首字前按阶段显示处理中，追问时先理解追问", async () => {
    let release = () => {};
    const released = new Promise<void>((resolve) => (release = resolve));
    server.use(
      http.post("/api/conversations/:id/turns/stream", () =>
        sseResponse(
          [
            ["turn", { id: 1 }],
            ["status", { text: "正在理解你的追问…" }],
            [
              "done",
              { id: 1, question: "那报损呢？", status: "ANSWERED", answer: null, knowledgeBaseIds: [1], askedAt: "" },
            ],
          ],
          released,
        ),
      ),
    );
    renderPage("/chat/901");
    await screen.findByRole("region", { name: "问答：临期商品怎么处理？" });
    ask("那报损呢？");

    expect(await screen.findByRole("status")).toHaveTextContent("正在理解你的追问…");
    expect(screen.getByRole("button", { name: "停止生成" })).toBeInTheDocument();
    release();
    await vi.waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
  });

  it("边生成边显示文字；拒答时用最终结果替换", async () => {
    let release = () => {};
    const released = new Promise<void>((resolve) => (release = resolve));
    server.use(
      http.post("/api/conversations/:id/turns/stream", () =>
        sseResponse(
          [
            ["turn", { id: 1 }],
            ["delta", { text: "这段文字没有引用" }],
            [
              "done",
              {
                id: 1,
                question: "明天会下雨吗？",
                status: "ANSWERED",
                answer: {
                  id: 801,
                  text: "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。",
                  refused: true,
                  refusalReason: "UNCITED",
                  citations: [],
                  topRelevance: 0.8,
                },
                knowledgeBaseIds: [1],
                askedAt: "",
              },
            ],
          ],
          released,
        ),
      ),
    );
    renderPage("/chat/901");
    await screen.findByRole("region", { name: "问答：临期商品怎么处理？" });
    ask("明天会下雨吗？");

    expect(await screen.findByText("这段文字没有引用")).toBeInTheDocument();
    release();
    expect(await screen.findByText("没有找到答案")).toBeInTheDocument();
    expect(screen.queryByText("这段文字没有引用")).not.toBeInTheDocument();
  });

  it("停止生成：调用停止接口，留下的部分有引用时照常显示并标“已停止生成”", async () => {
    Object.assign(streamTiming, { deltaMillis: 80 });
    const stops = spyRequests("POST", /\/turns\/\d+\/stop$/);
    renderPage("/chat/901");
    await screen.findByRole("region", { name: "问答：临期商品怎么处理？" });
    ask("那报损怎么登记？");

    await screen.findByText(/临期专区 \[1\]/, {}, { timeout: 3000 });
    fireEvent.click(screen.getByRole("button", { name: "停止生成" }));

    // 生成中的临时区块在 done 之后换成正式的一轮，要等正式的回答出现后再取。
    const stopped = await finishedTurn("问答：那报损怎么登记？");
    expect(within(stopped).getByText("已停止生成")).toBeInTheDocument();
    // 停在第二句之前：留下的文字只有第一条引用，没有生成完。
    expect(within(stopped).getByRole("article", { name: "回答" })).not.toHaveTextContent("店长确认");
    await vi.waitFor(() =>
      expect(stops.map((stop) => stop.path)).toEqual([
        expect.stringMatching(/^\/api\/conversations\/901\/turns\/\d+\/stop$/),
      ]),
    );
  });

  it("重新生成最后一条：带上被替换的那一轮，结果替换显示", async () => {
    const turns = spyRequests("POST", /\/turns\/stream$/);
    renderPage("/chat/901");

    fireEvent.click(await screen.findByRole("button", { name: "重新生成" }));

    await vi.waitFor(() =>
      expect(turns.map((turn) => turn.body)).toEqual([{ question: "临期商品怎么处理？", replacesTurnId: 9011 }]),
    );
    expect(await screen.findByRole("button", { name: "重新生成" })).toBeInTheDocument();
    expect(screen.getAllByRole("region", { name: "问答：临期商品怎么处理？" })).toHaveLength(1);
    expect(screen.getByRole("article", { name: "回答" })).toHaveTextContent("临期专区");
  });

  it("修改最后一个问题后重问：旧的一轮被替换", async () => {
    const turns = spyRequests("POST", /\/turns\/stream$/);
    renderPage("/chat/901");

    fireEvent.click(await screen.findByRole("button", { name: "修改问题" }));
    expect(screen.getByLabelText("输入问题")).toHaveValue("临期商品怎么处理？");
    ask("临期食品怎么打折？");

    expect(await screen.findByRole("region", { name: "问答：临期食品怎么打折？" })).toBeInTheDocument();
    await vi.waitFor(() =>
      expect(turns.map((turn) => turn.body)).toEqual([{ question: "临期食品怎么打折？", replacesTurnId: 9011 }]),
    );
    expect(screen.queryByRole("region", { name: "问答：临期商品怎么处理？" })).not.toBeInTheDocument();
  });

  it("寒暄只回一句话，不列来源", async () => {
    renderPage("/chat/901");
    await screen.findByRole("region", { name: "问答：临期商品怎么处理？" });
    ask("谢谢");

    const reply = await finishedTurn("问答：谢谢");
    expect(within(reply).getByRole("article", { name: "回答" })).toHaveTextContent("不客气，还有问题随时问我。");
    expect(within(reply).queryByRole("list", { name: "来源" })).not.toBeInTheDocument();
  });

  it("服务端报错时给出中文提示", async () => {
    server.use(
      http.post("/api/conversations/:id/turns/stream", () =>
        sseResponse([["error", { status: 503, detail: "对话模型服务暂时不可用" }]]),
      ),
    );
    renderPage("/chat/901");
    await screen.findByRole("region", { name: "问答：临期商品怎么处理？" });
    ask("那报损呢？");

    expect(await screen.findByText("暂时无法回答，请稍后重试。")).toBeInTheDocument();
  });
});

describe("反馈", () => {
  function recordFeedback() {
    const requests: { method: string; id: string; body: unknown }[] = [];
    const record = async ({ request, params }: { request: Request; params: Record<string, unknown> }) => {
      requests.push({
        method: request.method,
        id: String(params.id),
        body: request.method === "DELETE" ? null : await request.json(),
      });
      return new HttpResponse(null, { status: 204 });
    };
    server.use(http.put("/api/answers/:id/feedback", record), http.delete("/api/answers/:id/feedback", record));
    return requests;
  }

  it("点赞提交给后端，再点一次撤销", async () => {
    const requests = recordFeedback();
    renderPage("/chat/901");

    const helpful = await screen.findByRole("button", { name: "有帮助" });
    fireEvent.click(helpful);
    await vi.waitFor(() => expect(requests).toEqual([{ method: "PUT", id: "701", body: { rating: "UP" } }]));
    fireEvent.click(helpful);
    await vi.waitFor(() => expect(requests.at(-1)).toEqual({ method: "DELETE", id: "701", body: null }));
  });

  it("拒答也能反馈没帮助", async () => {
    const requests = recordFeedback();
    renderPage("/chat/904");

    fireEvent.click(await screen.findByRole("button", { name: "没帮助" }));
    await vi.waitFor(() => expect(requests[0]).toMatchObject({ method: "PUT", id: "703", body: { rating: "DOWN" } }));
  });
});
