import { ApiError, json, query } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/**
 * 知识质量：检索测试与标准题回归（#224、D-069、D-070）。标准题挂在题集上，题集可以跨库；
 * 每道题在一个知识库里检索，查看人或运行人无权访问这个库时，这道题当作不存在（不显示、不运行、不计分）。
 */

/** 期望：命中期望文档，或者应当拒答（资料里没有答案、超出范围的问题）。 */
export type Expectation = "DOCUMENTS" | "REFUSAL";
/** 期望多个文档时：全部命中才算通过，或命中任一份就算通过。 */
export type MatchMode = "ALL" | "ANY";

/** 期望文档。上传新版本后自动接续到新版本；删除后 deleted 为 true，这道题标为过期。 */
export type ExpectedDocument = { documentId: number; filename: string; deleted: boolean };

export type GoldenQuestion = {
  id: number;
  question: string;
  knowledgeBaseId: number;
  knowledgeBaseName: string;
  expectation: Expectation;
  match: MatchMode;
  expectedDocuments: ExpectedDocument[];
  /** 有期望文档已被删除：不参与运行，改好期望文档后恢复。 */
  stale: boolean;
  createdAt: string;
};

export type RunStatus = "RUNNING" | "DONE" | "FAILED";

/** 一次运行的概况；通过、未通过只数查看人能看到的题。 */
export type RunSummary = {
  id: number;
  status: RunStatus;
  /** 运行时参与的题数（跳过的过期题不算）；运行中时 done 是已跑完的题数。 */
  total: number;
  done: number;
  passed: number;
  failed: number;
  /** 和同一题集上一次运行比：上次通过这次没通过、上次没通过这次通过。 */
  newlyFailed: number;
  newlyPassed: number;
  runnerName: string;
  startedAt: string;
  finishedAt: string | null;
};

export type EvalSet = {
  id: number;
  name: string;
  description: string;
  /** 查看人能看到的题数和其中过期的题数。 */
  questionCount: number;
  staleCount: number;
  knowledgeBaseNames: string[];
  lastRun: RunSummary | null;
  updatedAt: string;
};

/** 和上一次运行比：NEW 表示上一次没有这道题。 */
export type Change = "NEWLY_FAILED" | "NEWLY_PASSED" | "SAME" | "NEW";

export type QuestionResult = {
  questionId: number;
  question: string;
  knowledgeBaseName: string;
  expectation: Expectation;
  match: MatchMode;
  passed: boolean;
  change: Change;
  refused: boolean;
  /** 召回、引用里命中的期望文档数；应当拒答的题为 0。 */
  expectedCount: number;
  recalledCount: number;
  citedCount: number;
  /** 没有召回的期望文档名，帮助判断是资料问题还是检索问题。 */
  missingDocuments: string[];
  answerText: string;
  citedDocuments: string[];
};

export type RunDetail = RunSummary & { setId: number; setName: string; results: QuestionResult[] };

/** 召回的一段原文：试问时列出，看检索有没有找到该找的资料。 */
export type RetrievedPassage = {
  documentId: number;
  documentName: string;
  headingPath: string;
  excerpt: string;
  /** 重排后的相关度，0～1。 */
  relevance: number;
  /** 回答里引用了这一段。 */
  cited: boolean;
};

/** 试问的结果：回答、引用的文档和召回的原文（重排后的前几条，不论是否达到门槛）。 */
export type TrialResult = {
  text: string;
  refused: boolean;
  refusalReason: string | null;
  citedDocuments: { documentId: number; documentName: string }[];
  passages: RetrievedPassage[];
};

/** 运行前的预估：每题按近 30 天每次回答的平均费用（单价表 D-065）估；没有单价或近期用量时 cost 为 null。 */
export type RunEstimate = { questions: number; skippedStale: number; cost: number | null; currency: string };

/** 试问和标准题可选的知识库：当前账号能访问的库。 */
export type KnowledgeBaseOption = { id: number; name: string };
export type DocumentOption = { id: number; filename: string };

export type QuestionDraft = {
  question: string;
  knowledgeBaseId: number;
  expectation: Expectation;
  match: MatchMode;
  expectedDocumentIds: number[];
};

export type ImportResult = { imported: number; skipped: { line: number; reason: string }[] };

const JSON_HEADERS = { "Content-Type": "application/json" };

async function noContent(response: Response, fallback: string) {
  if (!response.ok) await json(response, fallback);
}

/** 在一个知识库里试问：按当前账号的权限检索和回答，不写回答记录（eval:run）。 */
export async function trialAsk(knowledgeBaseId: number, question: string): Promise<TrialResult> {
  const response = await authFetch("/api/evaluation/trial", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify({ knowledgeBaseId, question }),
  });
  return json(response, "试问失败");
}

export async function fetchKnowledgeBaseOptions(): Promise<KnowledgeBaseOption[]> {
  return json(await authFetch("/api/evaluation/knowledge-bases"), "读取知识库失败");
}

/** 选期望文档：能访问的库里已入库的文档，按文件名搜，最多 20 份。 */
export async function fetchDocumentOptions(knowledgeBaseId: number, keyword: string): Promise<DocumentOption[]> {
  return json(
    await authFetch(`/api/evaluation/knowledge-bases/${knowledgeBaseId}/documents${query({ keyword })}`),
    "读取文档失败",
  );
}

export async function fetchSets(): Promise<EvalSet[]> {
  return json(await authFetch("/api/evaluation/sets"), "读取题集失败");
}

export async function fetchSet(id: number): Promise<EvalSet> {
  return json(await authFetch(`/api/evaluation/sets/${id}`), "读取题集失败");
}

export async function saveSet(id: number | null, body: { name: string; description: string }): Promise<EvalSet> {
  const response = await authFetch(id === null ? "/api/evaluation/sets" : `/api/evaluation/sets/${id}`, {
    method: id === null ? "POST" : "PUT",
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  });
  return json(response, "保存题集失败");
}

export async function deleteSet(id: number): Promise<void> {
  await noContent(await authFetch(`/api/evaluation/sets/${id}`, { method: "DELETE" }), "删除题集失败");
}

export async function fetchQuestions(setId: number): Promise<GoldenQuestion[]> {
  return json(await authFetch(`/api/evaluation/sets/${setId}/questions`), "读取标准题失败");
}

export async function saveQuestion(setId: number, id: number | null, draft: QuestionDraft): Promise<GoldenQuestion> {
  const response = await authFetch(
    id === null ? `/api/evaluation/sets/${setId}/questions` : `/api/evaluation/sets/${setId}/questions/${id}`,
    { method: id === null ? "POST" : "PUT", headers: JSON_HEADERS, body: JSON.stringify(draft) },
  );
  return json(response, "保存标准题失败");
}

export async function deleteQuestion(setId: number, id: number): Promise<void> {
  await noContent(
    await authFetch(`/api/evaluation/sets/${setId}/questions/${id}`, { method: "DELETE" }),
    "删除标准题失败",
  );
}

/** CSV 里的一行：知识库、期望文档按名称填；期望文档留空表示应当拒答。line 是文件里的行号，出错时提示用。 */
export type ImportRow = { line: number; question: string; knowledgeBase: string; documents: string[]; match: MatchMode };

/** 导入标准题：后端按当前账号能访问的库逐行匹配名称，匹配不上的行跳过并说明原因，其余照常导入。 */
export async function importQuestions(setId: number, rows: ImportRow[]): Promise<ImportResult> {
  const response = await authFetch(`/api/evaluation/sets/${setId}/questions/import`, {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify({ rows }),
  });
  return json(response, "导入失败");
}

export async function fetchEstimate(setId: number): Promise<RunEstimate> {
  return json(await authFetch(`/api/evaluation/sets/${setId}/estimate`), "预估费用失败");
}

/** 一键重跑整个题集（eval:run）：后台逐题运行，只跑运行人能访问的库里的题。 */
export async function startRun(setId: number): Promise<RunSummary> {
  return json(await authFetch(`/api/evaluation/sets/${setId}/runs`, { method: "POST" }), "开始运行失败");
}

export async function fetchRuns(setId: number): Promise<RunSummary[]> {
  return json(await authFetch(`/api/evaluation/sets/${setId}/runs`), "读取运行记录失败");
}

export async function fetchRun(id: number): Promise<RunDetail> {
  return json(await authFetch(`/api/evaluation/runs/${id}`), "读取运行结果失败");
}

/** 导出运行结果（CSV），只含查看人能看到的题。 */
export async function exportRun(id: number): Promise<Blob> {
  const response = await authFetch(`/api/evaluation/runs/${id}/export`);
  if (!response.ok) throw new ApiError(response.status, "导出失败");
  return response.blob();
}

export const EXPECTATION_LABELS: Record<Expectation, string> = { DOCUMENTS: "命中期望文档", REFUSAL: "应当拒答" };
export const MATCH_LABELS: Record<MatchMode, string> = { ALL: "全部命中", ANY: "任一命中" };

export const evaluationKeys = {
  all: ["evaluation"],
  knowledgeBases: ["evaluation", "knowledge-bases"],
  documents: (knowledgeBaseId: number, keyword: string) =>
    ["evaluation", "knowledge-bases", knowledgeBaseId, "documents", keyword] as const,
  sets: ["evaluation", "sets"],
  set: (id: number) => ["evaluation", "sets", id] as const,
  questions: (setId: number) => ["evaluation", "sets", setId, "questions"] as const,
  runs: (setId: number) => ["evaluation", "sets", setId, "runs"] as const,
  estimate: (setId: number) => ["evaluation", "sets", setId, "estimate"] as const,
  run: (id: number) => ["evaluation", "runs", id] as const,
} as const;
