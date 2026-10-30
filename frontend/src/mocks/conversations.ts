import { delay, http, HttpResponse } from "msw";
import type { Answer, Conversation, ConversationSummary, Turn } from "@/features/chat/api";

/**
 * 员工端会话的假接口（#107 原型）。契约与后端一致：只返回自己的会话；知识库按当前授权标出能否访问；
 * 提问的事件依次是 turn、status、delta、done（这一轮的最终结果）；只有 stop 接口会中止生成。
 */
type Store = {
  conversations: Array<ConversationSummary & { ownerId: number; turns: Turn[] }>;
  /** 会话和轮次各自编号，起点避开示例数据的 id（会话 901–904，轮次 9011 起）。 */
  nextConversationId: number;
  nextTurnId: number;
  stopped: Set<number>;
};

type Context = {
  currentUserId: () => number;
  knowledgeBaseName: (id: number) => string;
  /** 当前登录的账号能否访问这个知识库（是不是它的成员）。 */
  canAccess: (knowledgeBaseId: number) => boolean;
  accessibleKnowledgeBaseIds: () => number[];
};

const store: Store = { conversations: [], nextConversationId: 1000, nextTurnId: 20000, stopped: new Set() };

/** 让测试能把生成过程拉长或缩短；原型里每段间隔稍长，看得清处理中的状态。 */
export const streamTiming = { stepMillis: 400, deltaMillis: 60 };

const answerAboutExpiry: Answer = {
  id: 701,
  text: "保质期剩余三分之一时贴黄色临期标签并移到临期专区 [1]；剩余 1 天的商品当天闭店前下架，登记报损后由店长确认 [2]。",
  refused: false,
  refusalReason: null,
  citations: [
    {
      index: 1,
      chunkId: 101,
      documentId: 11,
      documentName: "临期与报损管理.md",
      headingPath: "临期商品 > 下架时限",
      excerpt: "保质期剩余不足三分之一的商品，贴黄色临期标签，集中陈列到临期专区，按原价七折销售。",
    },
    {
      index: 2,
      chunkId: 102,
      documentId: 11,
      documentName: "临期与报损管理.md",
      headingPath: "报损 > 登记与确认",
      excerpt: "保质期仅剩 1 天的商品，当天闭店前全部下架，在报损登记表写明品名、数量和原因，店长签字确认。",
    },
  ],
  topRelevance: 0.97,
};

const answerAboutFrozen: Answer = {
  id: 702,
  text: "冷冻柜温度要保持在 -18℃ 以下 [1]，每天开店和闭店各记录一次 [1]。",
  refused: false,
  refusalReason: null,
  citations: [
    {
      index: 1,
      chunkId: 103,
      documentId: 13,
      documentName: "商品陈列与补货规范.md",
      headingPath: "冷链 > 冷冻柜",
      excerpt: "冷冻柜温度保持在 -18℃ 以下，开店、闭店各记录一次温度，异常时立即报告店长。",
    },
  ],
  topRelevance: 0.93,
};

const refusal: Answer = {
  id: 703,
  text: "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。",
  refused: true,
  refusalReason: "LOW_RELEVANCE",
  citations: [],
  topRelevance: 0.04,
};

/** 寒暄：模型判断不需要检索，只回没有事实内容的话（D-036）。 */
const smallTalk: Answer = {
  id: 704,
  text: "不客气，还有问题随时问我。",
  refused: false,
  refusalReason: null,
  citations: [],
  topRelevance: null,
};

function daysAgo(days: number, hour = 10): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  date.setHours(hour, 0, 0, 0);
  return date.toISOString();
}

function turn(id: number, question: string, answer: Answer | null, knowledgeBaseIds: number[], askedAt: string): Turn {
  return { id, question, status: answer ? "ANSWERED" : "FAILED", answer, knowledgeBaseIds, askedAt };
}

/** 示例会话属于店员小王（id 2）。他只是“门店运营知识库”（1）的成员；会话 902 里选过的“商品知识库”（2）已无权访问。 */
export function resetConversations() {
  store.stopped.clear();
  store.nextConversationId = 1000;
  store.nextTurnId = 20000;
  store.conversations = [
    {
      id: 901,
      ownerId: 2,
      title: "临期商品处理",
      knowledgeBaseIds: [1],
      lastAskedAt: daysAgo(0),
      turns: [turn(9011, "临期商品怎么处理？", answerAboutExpiry, [1], daysAgo(0))],
    },
    {
      id: 902,
      ownerId: 2,
      title: "冷柜与冷冻柜温度",
      knowledgeBaseIds: [1, 2],
      lastAskedAt: daysAgo(1),
      turns: [
        turn(
          9021,
          "饮料冷柜温度要多少？",
          { ...answerAboutFrozen, id: 705, text: "饮料冷柜温度保持在 2–6℃ [1]。" },
          [1, 2],
          daysAgo(1),
        ),
        turn(
          9022,
          "那冷冻柜呢？",
          {
            ...answerAboutFrozen,
            citations: answerAboutFrozen.citations.map((citation) => ({ ...citation, excerpt: null })),
          },
          [1, 2],
          daysAgo(1),
        ),
      ],
    },
    {
      id: 903,
      ownerId: 2,
      title: "交班差额处理",
      knowledgeBaseIds: [1],
      lastAskedAt: daysAgo(4),
      turns: [turn(9031, "交班差额超过多少要写说明？", { ...answerAboutExpiry, id: 706 }, [1], daysAgo(4))],
    },
    {
      id: 904,
      ownerId: 2,
      title: "员工年假怎么请",
      knowledgeBaseIds: [1],
      lastAskedAt: daysAgo(20),
      turns: [turn(9041, "员工年假怎么请？", refusal, [1], daysAgo(20))],
    },
  ];
}

function summary(conversation: Store["conversations"][number]): ConversationSummary {
  const { id, title, knowledgeBaseIds, lastAskedAt } = conversation;
  return { id, title, knowledgeBaseIds, lastAskedAt };
}

function detail(conversation: Store["conversations"][number], context: Context): Conversation {
  return {
    id: conversation.id,
    title: conversation.title,
    lastAskedAt: conversation.lastAskedAt,
    knowledgeBases: conversation.knowledgeBaseIds.map((id) => ({
      id,
      name: context.knowledgeBaseName(id),
      accessible: context.canAccess(id),
    })),
    turns: conversation.turns,
  };
}

function notFound() {
  return HttpResponse.json(
    { status: 404, code: "ANSWERING_NOT_FOUND", detail: "会话不存在" },
    { status: 404, headers: { "Content-Type": "application/problem+json" } },
  );
}

/** 按问题挑一个示例回答：含“谢谢”是寒暄，含“年假”找不到，含“冷冻”答冷冻柜，其余答临期。 */
function answerFor(question: string): Answer {
  if (question.includes("谢谢")) return smallTalk;
  if (question.includes("年假")) return refusal;
  if (question.includes("冷冻")) return answerAboutFrozen;
  return answerAboutExpiry;
}

/** 模拟后台生成的标题：取问题去掉标点后的前 12 个字。 */
function titleFor(question: string): string {
  return question.replace(/[？?。！!，,\s]/g, "").slice(0, 12) || "新对话";
}

export function conversationHandlers(context: Context) {
  const own = (id: unknown) =>
    store.conversations.find(
      (conversation) => conversation.id === Number(id) && conversation.ownerId === context.currentUserId(),
    );

  return [
    http.get("/api/conversations", () =>
      HttpResponse.json(
        store.conversations
          .filter((conversation) => conversation.ownerId === context.currentUserId())
          .sort((a, b) => b.lastAskedAt.localeCompare(a.lastAskedAt))
          .map(summary),
      ),
    ),
    http.post("/api/conversations", async ({ request }) => {
      const body = (await request.json().catch(() => ({}))) as { knowledgeBaseIds?: number[] };
      const accessible = context.accessibleKnowledgeBaseIds();
      // 点名无权限的库照旧忽略；没选或全被忽略时选中全部已授权的库。
      const chosen = (body.knowledgeBaseIds ?? []).filter((id) => accessible.includes(id));
      const created = {
        id: store.nextConversationId++,
        ownerId: context.currentUserId(),
        title: "新对话",
        knowledgeBaseIds: chosen.length > 0 ? chosen : accessible,
        lastAskedAt: new Date().toISOString(),
        turns: [],
      };
      store.conversations.push(created);
      return HttpResponse.json(summary(created), { status: 201 });
    }),
    http.get("/api/conversations/:id", ({ params }) => {
      const conversation = own(params.id);
      return conversation ? HttpResponse.json(detail(conversation, context)) : notFound();
    }),
    http.patch("/api/conversations/:id", async ({ params, request }) => {
      const conversation = own(params.id);
      if (!conversation) return notFound();
      const change = (await request.json()) as { title?: string; knowledgeBaseIds?: number[] };
      if (change.title !== undefined) {
        conversation.title = change.title.trim() || conversation.title;
      }
      if (change.knowledgeBaseIds !== undefined) {
        const accessible = context.accessibleKnowledgeBaseIds();
        conversation.knowledgeBaseIds = change.knowledgeBaseIds.filter((id) => accessible.includes(id));
      }
      return HttpResponse.json(summary(conversation));
    }),
    http.delete("/api/conversations/:id", ({ params }) => {
      const conversation = own(params.id);
      if (!conversation) return notFound();
      store.conversations = store.conversations.filter((candidate) => candidate !== conversation);
      return new HttpResponse(null, { status: 204 });
    }),
    http.post("/api/conversations/:id/turns/:turnId/stop", ({ params }) => {
      if (!own(params.id)) return notFound();
      store.stopped.add(Number(params.turnId));
      return new HttpResponse(null, { status: 204 });
    }),
    http.post("/api/conversations/:id/turns/stream", async ({ params, request }) => {
      const conversation = own(params.id);
      if (!conversation) return notFound();
      const { question, replacesTurnId } = (await request.json()) as { question: string; replacesTurnId?: number };
      const turnId = store.nextTurnId++;
      const hasHistory = conversation.turns.some((candidate) => candidate.id !== replacesTurnId);
      const knowledgeBaseIds = conversation.knowledgeBaseIds.filter(context.canAccess);
      const answer = answerFor(question);
      const encoder = new TextEncoder();
      const send = (controller: ReadableStreamDefaultController<Uint8Array>, name: string, data: unknown) =>
        controller.enqueue(encoder.encode(`event:${name}\ndata:${JSON.stringify(data)}\n\n`));

      const body = new ReadableStream<Uint8Array>({
        async start(controller) {
          send(controller, "turn", { id: turnId });
          const steps = [
            ...(hasHistory ? ["正在理解你的追问…"] : []),
            ...(answer === smallTalk ? [] : ["正在检索资料…", "正在整理答案…"]),
          ];
          for (const text of steps) {
            send(controller, "status", { text });
            await delay(streamTiming.stepMillis);
          }
          let streamed = "";
          for (const piece of answer.refused ? [] : (answer.text.match(/.{1,6}/gu) ?? [])) {
            if (store.stopped.has(turnId)) break;
            streamed += piece;
            send(controller, "delta", { text: piece });
            await delay(streamTiming.deltaMillis);
          }
          const stopped = store.stopped.has(turnId);
          // 停止时已生成的部分照样做引用校验：留下的文字里有引用才算回答（D-036）。
          const kept = stopped
            ? answer.citations.filter((citation) => streamed.includes(`[${citation.index}]`))
            : answer.citations;
          const final: Turn = {
            id: turnId,
            question,
            status: stopped ? "STOPPED" : "ANSWERED",
            answer: stopped ? (kept.length > 0 ? { ...answer, text: streamed, citations: kept } : null) : answer,
            knowledgeBaseIds,
            askedAt: new Date().toISOString(),
          };
          const isFirst = conversation.turns.length === 0;
          conversation.turns = [...conversation.turns.filter((candidate) => candidate.id !== replacesTurnId), final];
          conversation.lastAskedAt = final.askedAt;
          if (isFirst) {
            conversation.title = titleFor(question);
          }
          send(controller, "done", final);
          controller.close();
        },
      });
      return new HttpResponse(body, { headers: { "Content-Type": "text/event-stream" } });
    }),
  ];
}
