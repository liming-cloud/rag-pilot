import { authFetch } from "@/lib/auth";

/** 与后端 answering 模块的 Citation 一致。 */
export type Citation = {
  index: number;
  chunkId: number;
  documentId: number;
  documentName: string;
  headingPath: string;
  excerpt: string;
};

/** 与后端 answering 模块的 Answer 一致；拒答时 text 是给用户看的说明，citations 为空。 */
export type Answer = {
  text: string;
  refused: boolean;
  refusalReason: "NO_EVIDENCE" | "LOW_RELEVANCE" | "MODEL_DECLINED" | "UNCITED" | null;
  citations: Citation[];
  topRelevance: number | null;
};

/**
 * 流式问答：读后端 SSE，每段文字交给 onDelta，返回 done 事件里的最终结果。
 * 拒答时最终结果和已显示的文字不同，调用方要用它替换。不传知识库，由后端按成员授权决定范围。
 */
export async function askStream(question: string, onDelta: (text: string) => void): Promise<Answer> {
  const response = await authFetch("/api/ask/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({ question }),
  });
  if (!response.ok || !response.body) {
    throw new Error(`问答失败（${response.status}）`);
  }
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
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
      if (event?.name === "delta") {
        onDelta((event.data as { text: string }).text);
      } else if (event?.name === "done") {
        return event.data as Answer;
      } else if (event?.name === "error") {
        throw new Error((event.data as { detail: string }).detail);
      }
    }
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
