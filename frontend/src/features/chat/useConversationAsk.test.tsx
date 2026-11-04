import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { streamTiming } from "@/mocks/conversations";
import { server } from "@/mocks/server";
import { chatKeys, type Conversation, type ConversationKnowledgeBase, fetchConversation } from "./api";
import { useConversationAsk } from "./useConversationAsk";

const STORE: ConversationKnowledgeBase = { id: 1, name: "门店运营知识库", accessible: true };
const saved = { ...streamTiming };
beforeEach(() => Object.assign(streamTiming, { stepMillis: 0, deltaMillis: 0 }));
afterEach(() => Object.assign(streamTiming, saved));

/** 在内存路由里跑 hook，好看到新建会话后跳到了哪里。示例会话 901 属于默认登录的店员小王。 */
function setup(conversationId: number | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  let hook: ReturnType<typeof useConversationAsk> | undefined;
  function Probe() {
    hook = useConversationAsk(conversationId, [STORE.id], [STORE]);
    return null;
  }
  const router = createMemoryRouter(
    [
      { path: "/chat", element: <Probe /> },
      { path: "/chat/:id", element: null },
    ],
    { initialEntries: ["/chat"] },
  );
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
      {children}
    </QueryClientProvider>
  );
  renderHook(() => null, { wrapper });
  return { client, router, current: () => hook! };
}

describe("在会话里提问（#145 useConversationAsk）", () => {
  it("在已有会话里提问：生成期间有处理中的一轮，结束后这一轮写进会话缓存、不再有处理中的一轮", async () => {
    const { client, current } = setup(901);
    client.setQueryData(chatKeys.conversation(901), await fetchConversation(901));
    const before = client.getQueryData<Conversation>(chatKeys.conversation(901))!.turns.length;

    let done: Promise<void> | undefined;
    act(() => {
      done = current().ask("冷柜温度要多少？");
    });
    expect(current().asking).toBe(true);
    expect(current().live).toMatchObject({ conversationId: 901, question: "冷柜温度要多少？" });
    await act(() => done!);

    expect(current().live).toBeNull();
    expect(current().asking).toBe(false);
    const turns = client.getQueryData<Conversation>(chatKeys.conversation(901))!.turns;
    expect(turns).toHaveLength(before + 1);
    expect(turns.at(-1)).toMatchObject({ question: "冷柜温度要多少？", status: "ANSWERED" });
  });

  it("还没有会话时先新建一个（带上选中的知识库）并跳过去，缓存里先放好这个会话", async () => {
    const { client, router, current } = setup(null);

    await act(() => current().ask("交班要做什么？"));

    const match = /^\/chat\/(\d+)$/.exec(router.state.location.pathname);
    expect(match, router.state.location.pathname).not.toBeNull();
    const created = client.getQueryData<Conversation>(chatKeys.conversation(Number(match![1])));
    expect(created?.knowledgeBases).toEqual([STORE]);
    expect(created?.turns.at(-1)?.question).toBe("交班要做什么？");
  });

  it("提问失败时这一轮标成失败，不再算作生成中", async () => {
    server.use(http.post("/api/conversations/:id/turns/stream", () => HttpResponse.json({}, { status: 500 })));
    const { current } = setup(901);

    await act(() => current().ask("冷柜温度要多少？"));

    expect(current().live).toMatchObject({ failed: true });
    expect(current().asking).toBe(false);
  });

  it("停止生成：拿到这一轮的 id 后发停止请求；还没拿到 id 时什么也不发", async () => {
    Object.assign(streamTiming, { deltaMillis: 50 });
    const stops: string[] = [];
    server.events.on("request:start", ({ request }) => {
      if (request.method === "POST" && request.url.endsWith("/stop")) stops.push(new URL(request.url).pathname);
    });
    const { current } = setup(901);

    await act(() => current().stop());
    expect(stops).toEqual([]);

    let done: Promise<void> | undefined;
    act(() => {
      done = current().ask("冷柜温度要多少？");
    });
    await waitFor(() => expect(current().live?.turnId).toBeDefined());
    const turnId = current().live!.turnId;
    await act(() => current().stop());
    await act(() => done!);

    expect(stops).toEqual([`/api/conversations/901/turns/${turnId}/stop`]);
    server.events.removeAllListeners("request:start");
  });
});
