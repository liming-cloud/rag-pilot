import { json } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 与后端 answering 模块的 Citation 一致。 */
export type Citation = {
  index: number;
  chunkId: number;
  documentId: number;
  documentName: string;
  headingPath: string;
  /** 原文片段；提问人已无权访问这条引用所在的知识库时为 null，界面显示“已无权查看原文”（D-036）。 */
  excerpt: string | null;
};

/** 与后端 answering 模块的 Answer 一致；拒答时 text 是给用户看的说明，citations 为空。id 用于点赞点踩。 */
export type Answer = {
  id: number | null;
  text: string;
  refused: boolean;
  refusalReason: "NO_EVIDENCE" | "LOW_RELEVANCE" | "MODEL_DECLINED" | "UNCITED" | null;
  citations: Citation[];
  topRelevance: number | null;
};

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
  });
  if (!response.ok || !response.body) {
    throw new Error(`问答失败（${response.status}）`);
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
        throw new Error((event.data as { detail: string }).detail);
      }
      on[event.name]?.(event.data);
    }
  }
}
