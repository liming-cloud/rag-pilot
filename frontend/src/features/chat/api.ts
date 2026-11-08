import { ApiError, json } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 与后端 answering 模块的 Citation 一致。 */
export type Citation = {
  index: number;
  chunkId: number;
  documentId: number;
  documentName: string;
  headingPath: string;
  /** 原文片段；按当前授权读取，知识库授权已收回或原文已删除时为 null，界面显示“已无权查看原文”（D-036）。 */
  excerpt: string | null;
  /** 资料最后一次上传或替换的时间（#145 A7）；原文已删除时为 null。 */
  documentUpdatedAt: string | null;
  /** 资料现在的状态：仍有效、已过失效日期、已被新版本替换（#145 A7）。 */
  documentState: "CURRENT" | "EXPIRED" | "REPLACED";
};

/** 与后端 answering 模块的 DataCitation 一致（#182）：回答引用的数据出处，序号与资料引用是同一套。 */
export type DataCitation = {
  index: number;
  tool: string;
  /** 来源系统和数据集，如 ERP / store_inventory。 */
  system: string;
  dataset: string;
  /** 客户系统实际执行的查询条件，如“store_id=S001，sku=A1001”。 */
  conditions: string;
  /** 数据截至时间；实时数据等于查询时间。 */
  asOf: string;
  queriedAt: string;
  /** 结果超过条数上限，只列出了一部分。 */
  truncated: boolean;
};

/**
 * 与后端 answering 模块的 Answer 一致；拒答时 text 是给用户看的说明，citations、dataCitations 为空。id 用于点赞点踩。
 * 拒答原因 DATA_DENIED、DATA_UNAVAILABLE：问题要查数据，但无权查看或暂时查不到，资料里也没有答案（#182）。
 */
export type Answer = {
  id: number | null;
  text: string;
  refused: boolean;
  refusalReason:
    | "NO_EVIDENCE"
    | "LOW_RELEVANCE"
    | "MODEL_DECLINED"
    | "UNCITED"
    | "DATA_DENIED"
    | "DATA_UNAVAILABLE"
    | null;
  citations: Citation[];
  dataCitations: DataCitation[];
  topRelevance: number | null;
  /** 低分改写和各阶段耗时，评测报告用，界面不显示。 */
  rewrite?: Record<string, unknown> | null;
  timings?: Record<string, unknown> | null;
};

/**
 * 记下员工点开了这条回答的第几个引用（#145 A11，算引用点开率）。只记哪条回答、第几个引用和时间；
 * 上报失败不影响员工看原文，所以不抛出。
 */
export async function reportCitationOpened(answerId: number, index: number): Promise<void> {
  await authFetch(`/api/answers/${answerId}/citations/${index}/opened`, { method: "POST" }).catch(() => undefined);
}

/** 点赞、点踩（可附留言）或撤销（rating 为 null）。只有提问人本人能评价自己的回答。 */
export async function rateAnswer(id: number, rating: "UP" | "DOWN" | null, comment?: string): Promise<void> {
  const response = await authFetch(`/api/answers/${id}/feedback`, {
    method: rating ? "PUT" : "DELETE",
    headers: rating ? { "Content-Type": "application/json" } : undefined,
    body: rating ? JSON.stringify(comment ? { rating, comment } : { rating }) : undefined,
  });
  if (!response.ok) {
    throw new Error(`反馈失败（${response.status}）`);
  }
}

function parseEvent(block: string): { name: string; data: unknown } | null {
  let name = "";
  let data = "";
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) {
      name = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      data += line.slice("data:".length);
    }
  }
  return name ? { name, data: JSON.parse(data) as unknown } : null;
}

/** 侧栏里的一个会话。只返回提问人自己的会话，按最后一次提问倒序（D-036）。 */
export type ConversationSummary = {
  id: number;
  /** 第一轮回答后由模型在后台生成；生成前是第一个问题的截断。 */
  title: string;
  /** 会话选中的知识库；新会话默认是全部已授权的库。 */
  knowledgeBaseIds: number[];
  lastAskedAt: string;
};

/** 会话选中的一个知识库。accessible 为 false 表示授权已被收回：后续提问不再检索它。 */
export type ConversationKnowledgeBase = { id: number; name: string; accessible: boolean };

/**
 * 会话里的一轮问答。重新生成、修改问题后，旧的一轮作为兄弟消息留在后端供审计，这里只返回当前主线。
 * STOPPED：员工点了停止，已生成的部分照样做引用校验，没有有效引用时 answer 为 null。
 */
export type Turn = {
  id: number;
  question: string;
  status: "ANSWERED" | "STOPPED" | "FAILED";
  answer: Answer | null;
  /** 这一轮实际检索的知识库。 */
  knowledgeBaseIds: number[];
  askedAt: string;
};

export type Conversation = Omit<ConversationSummary, "knowledgeBaseIds"> & {
  knowledgeBases: ConversationKnowledgeBase[];
  turns: Turn[];
};

export async function fetchConversations(): Promise<ConversationSummary[]> {
  return json(await authFetch("/api/conversations"), "读取会话失败");
}

export async function fetchConversation(id: number): Promise<Conversation> {
  return json(await authFetch(`/api/conversations/${id}`), "读取会话失败");
}

/** 新建会话；不传知识库时由后端选中全部已授权的库。 */
export async function createConversation(knowledgeBaseIds?: number[]): Promise<ConversationSummary> {
  const response = await authFetch("/api/conversations", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(knowledgeBaseIds ? { knowledgeBaseIds } : {}),
  });
  return json(response, "新建会话失败");
}

/** 重命名或改选知识库；改选从下一个问题起生效。 */
export async function updateConversation(
  id: number,
  change: { title?: string; knowledgeBaseIds?: number[] },
): Promise<ConversationSummary> {
  const response = await authFetch(`/api/conversations/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(change),
  });
  return json(response, "修改会话失败");
}

/** 员工删除会话只影响员工端，管理端的回答记录照常保留（D-036）。 */
export async function deleteConversation(id: number): Promise<void> {
  const response = await authFetch(`/api/conversations/${id}`, { method: "DELETE" });
  if (!response.ok) {
    throw new Error(`删除会话失败（${response.status}）`);
  }
}

/** 停止生成。只有这个操作会中止生成；断开连接、刷新页面时服务端会把这一轮生成完（D-036）。 */
export async function stopTurn(conversationId: number, turnId: number): Promise<void> {
  const response = await authFetch(`/api/conversations/${conversationId}/turns/${turnId}/stop`, { method: "POST" });
  if (!response.ok) {
    throw new Error(`停止失败（${response.status}）`);
  }
}

export type TurnHandlers = {
  /** 这一轮在服务端建好时调用，停止生成要用它的 id。 */
  onTurn?: (turnId: number) => void;
  onStatus?: (text: string) => void;
  onDelta?: (text: string) => void;
  /** 拿到轮次 id 之前点停止时用它断开请求（#145 A5）。 */
  signal?: AbortSignal;
};

/**
 * 在会话里提问（SSE）。事件依次是 turn、若干 status 和 delta、done（这一轮的最终结果）。
 * replacesTurnId 指向最后一轮时，是“重新生成”（问题不变）或“修改问题后重问”。
 */
export async function askInConversation(
  conversationId: number,
  request: { question: string; replacesTurnId?: number },
  handlers: TurnHandlers = {},
): Promise<Turn> {
  const response = await authFetch(`/api/conversations/${conversationId}/turns/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify(request),
    signal: handlers.signal,
  });
  if (!response.ok) {
    // 问题太长、会话刚被改过、提问太频繁等，显示服务端给的原因（#145 A2）。
    await json(response, "问答失败，请稍后重试");
  }
  if (!response.body) {
    throw new Error("问答失败，请稍后重试");
  }
  return readEvents<Turn>(response.body, {
    turn: (data) => handlers.onTurn?.((data as { id: number }).id),
    status: (data) => handlers.onStatus?.((data as { text: string }).text),
    delta: (data) => handlers.onDelta?.((data as { text: string }).text),
  });
}

/** 读完 SSE：按事件名分发，遇到 done 返回结果，遇到 error 抛出。 */
async function readEvents<T>(
  body: NonNullable<Response["body"]>,
  on: Record<string, (data: unknown) => void>,
): Promise<T> {
  const reader = body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      throw new Error("问答中断：没有收到最终结果");
    }
    buffer += value;
    // 事件之间以空行分隔；最后一段可能还没收完，留到下次。
    const blocks = buffer.split("\n\n");
    buffer = blocks.pop() ?? "";
    for (const block of blocks) {
      const event = parseEvent(block);
      if (!event) continue;
      if (event.name === "done") {
        return event.data as T;
      }
      if (event.name === "error") {
        const problem = event.data as { code?: string; detail: string };
        throw new ApiError(200, problem.detail, problem.code);
      }
      on[event.name]?.(event.data);
    }
  }
}

/**
 * 问答页的文案和推荐问题（D-045），默认来自行业包，管理员可在系统设置里改。refusalTips、refusalContact 是答不上来时给的下一步（#145 A8，行业包配置）：
 * 换个问法的建议，以及还找不到时该问谁。
 */
export type ChatTexts = {
  placeholder: string;
  emptyHint: string;
  suggestedQuestions: string[];
  refusalTips: string[];
  refusalContact: string;
};

/** 读取失败或还没读到时用的内核默认值，不带行业内容。 */
export const DEFAULT_CHAT_TEXTS: ChatTexts = {
  placeholder: "输入问题…",
  emptyHint: "问问知识库里的内容，回答会标出原文出处，可以接着追问。",
  suggestedQuestions: [],
  refusalTips: ["换个说法，或者说出具体的名称、场景再问一次。"],
  refusalContact: "",
};

export async function fetchChatTexts(): Promise<ChatTexts> {
  return json(await authFetch("/api/settings/chat"), "读取问答页文案失败");
}

/** 问答页用到的查询键（#145：查询键收在各自的 api.ts）。 */
export const chatKeys = {
  conversations: ["conversations"],
  /** id 为 null 时查询不启用（还没有会话）。 */
  conversation: (id: number | null) => ["conversation", id] as const,
  texts: ["chat-texts"],
} as const;
