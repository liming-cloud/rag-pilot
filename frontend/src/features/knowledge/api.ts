/** 与后端 KnowledgeBaseController 的响应一致。 */
export type KnowledgeBase = { id: number; name: string };

export async function fetchKnowledgeBases(): Promise<KnowledgeBase[]> {
  const response = await fetch("/api/knowledge-bases");
  if (!response.ok) {
    throw new Error(`读取知识库失败（${response.status}）`);
  }
  return (await response.json()) as KnowledgeBase[];
}
