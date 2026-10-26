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

export async function askQuestion(question: string, knowledgeBaseIds: number[]): Promise<Answer> {
  const response = await fetch("/api/ask", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question, knowledgeBaseIds }),
  });
  if (!response.ok) {
    throw new Error(`问答失败（${response.status}）`);
  }
  return (await response.json()) as Answer;
}
