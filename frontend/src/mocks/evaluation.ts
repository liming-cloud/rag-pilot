import { http, HttpResponse } from "msw";
import type { ImportResult, ImportRow, QuestionDraft, TrialResult } from "@/features/evaluation/api";
import {
  ago,
  COST_PER_QUESTION,
  db,
  detail,
  isStale,
  progress,
  questionView,
  setView,
  simulateRun,
  type StoredQuestion,
  summary,
  visibleQuestions,
} from "./evaluation-store";
import { currentIdentity, forbidden } from "./identity";
import { accessibleKnowledgeBaseIds, canAccess, documentsIn, knowledgeBaseName } from "./knowledge";
import { problem } from "./page";

export { resetEvaluation } from "./evaluation-store";

/** 知识质量的假接口（#224），数据和“后台运行”的模拟在 evaluation-store.ts。 */
function trial(knowledgeBaseId: number, question: string): TrialResult {
  const documents = documentsIn(knowledgeBaseId).slice(0, 3);
  const offTopic = /股票|天气|快递/.test(question) || documents.length === 0;
  const passages = documents.map((document, index) => ({
    documentId: document.id,
    documentName: document.filename,
    headingPath: index === 0 ? "总则 > 适用范围" : `第 ${index + 1} 章`,
    excerpt: `${document.filename.replace(/\.\w+$/, "")}：门店应按规定执行，发现问题当班登记并上报店长。`,
    relevance: offTopic ? 0.12 - index * 0.03 : 0.86 - index * 0.21,
    cited: !offTopic && index < 2,
  }));
  const cited = passages.filter((passage) => passage.cited);
  return {
    answer: {
      id: null,
      text: offTopic ? "资料里没有找到相关内容。" : "门店应按规定执行，发现问题当班登记并上报店长[1]。具体折扣以当月通知为准[2]。",
      refused: offTopic,
      refusalReason: offTopic ? "LOW_RELEVANCE" : null,
      citations: cited.map((passage, index) => ({
        index: index + 1,
        chunkId: passage.documentId * 10,
        documentId: passage.documentId,
        documentName: passage.documentName,
        headingPath: passage.headingPath,
        excerpt: passage.excerpt,
        documentUpdatedAt: ago(5),
        documentState: "CURRENT",
      })),
      dataCitations: [],
      topRelevance: passages[0]?.relevance ?? null,
    },
    passages,
  };
}

function storeQuestion(setId: number, draft: QuestionDraft, id: number, createdAt: string): StoredQuestion {
  const documents = documentsIn(draft.knowledgeBaseId);
  return {
    id,
    setId,
    question: draft.question.trim(),
    knowledgeBaseId: draft.knowledgeBaseId,
    expectation: draft.expectation,
    match: draft.match,
    expected: draft.expectation === "REFUSAL" ? [] : draft.expectedDocumentIds.map((documentId) => ({
      documentId,
      filename: documents.find((document) => document.id === documentId)?.filename ?? `文档 ${documentId}`,
    })),
    createdAt,
  };
}

function invalidDraft(draft: QuestionDraft) {
  if (!draft.question.trim()) return problem(400, "VALIDATION_FAILED", "请填写问题");
  if (!canAccess(draft.knowledgeBaseId)) return problem(404, "NOT_FOUND", "知识库不存在或无权访问");
  if (draft.expectation === "DOCUMENTS" && draft.expectedDocumentIds.length === 0) {
    return problem(400, "VALIDATION_FAILED", "请至少选一份期望文档，或改为“应当拒答”");
  }
  return null;
}

const findSet = (id: string | readonly string[] | undefined) => db.sets.find((set) => set.id === Number(id));

export const evaluationHandlers = [
  http.post("/api/evaluation/trial", async ({ request }) => {
    const denied = forbidden("eval:run");
    if (denied) return denied;
    const { knowledgeBaseId, question } = (await request.json()) as { knowledgeBaseId: number; question: string };
    if (!canAccess(knowledgeBaseId)) return problem(404, "NOT_FOUND", "知识库不存在或无权访问");
    return HttpResponse.json(trial(knowledgeBaseId, question));
  }),
  http.get("/api/evaluation/sets", () => forbidden("eval:view") ?? HttpResponse.json(db.sets.map(setView))),
  http.get("/api/evaluation/sets/:id", ({ params }) => {
    const set = findSet(params.id);
    return forbidden("eval:view") ?? (set ? HttpResponse.json(setView(set)) : problem(404, "NOT_FOUND", "题集不存在"));
  }),
  http.post("/api/evaluation/sets", async ({ request }) => {
    const denied = forbidden("eval:manage");
    if (denied) return denied;
    const body = (await request.json()) as { name: string; description: string };
    const set = { id: db.nextId++, name: body.name.trim(), description: body.description.trim(), updatedAt: new Date().toISOString() };
    db.sets.push(set);
    return HttpResponse.json(setView(set), { status: 201 });
  }),
  http.put("/api/evaluation/sets/:id", async ({ params, request }) => {
    const denied = forbidden("eval:manage");
    const set = findSet(params.id);
    if (denied || !set) return denied ?? problem(404, "NOT_FOUND", "题集不存在");
    Object.assign(set, (await request.json()) as { name: string; description: string }, { updatedAt: new Date().toISOString() });
    return HttpResponse.json(setView(set));
  }),
  http.delete("/api/evaluation/sets/:id", ({ params }) => {
    const denied = forbidden("eval:manage");
    if (denied) return denied;
    db.sets = db.sets.filter((set) => set.id !== Number(params.id));
    return new HttpResponse(null, { status: 204 });
  }),
  http.get("/api/evaluation/sets/:id/questions", ({ params }) =>
    forbidden("eval:view") ?? HttpResponse.json(visibleQuestions(Number(params.id)).map(questionView)),
  ),
  http.post("/api/evaluation/sets/:id/questions", async ({ params, request }) => {
    const draft = (await request.json()) as QuestionDraft;
    const invalid = forbidden("eval:manage") ?? invalidDraft(draft);
    if (invalid) return invalid;
    const question = storeQuestion(Number(params.id), draft, db.nextId++, new Date().toISOString());
    db.questions.push(question);
    return HttpResponse.json(questionView(question), { status: 201 });
  }),
  http.put("/api/evaluation/sets/:id/questions/:questionId", async ({ params, request }) => {
    const draft = (await request.json()) as QuestionDraft;
    const index = db.questions.findIndex((question) => question.id === Number(params.questionId) && canAccess(question.knowledgeBaseId));
    const invalid = forbidden("eval:manage") ?? (index < 0 ? problem(404, "NOT_FOUND", "标准题不存在") : invalidDraft(draft));
    if (invalid) return invalid;
    db.questions[index] = storeQuestion(Number(params.id), draft, db.questions[index]!.id, db.questions[index]!.createdAt);
    return HttpResponse.json(questionView(db.questions[index]));
  }),
  http.delete("/api/evaluation/sets/:id/questions/:questionId", ({ params }) => {
    const denied = forbidden("eval:manage");
    if (denied) return denied;
    db.questions = db.questions.filter((question) => !(question.id === Number(params.questionId) && canAccess(question.knowledgeBaseId)));
    return new HttpResponse(null, { status: 204 });
  }),
  http.post("/api/evaluation/sets/:id/questions/import", async ({ params, request }) => {
    const denied = forbidden("eval:manage");
    if (denied) return denied;
    const { rows } = (await request.json()) as { rows: ImportRow[] };
    const skipped: ImportResult["skipped"] = [];
    for (const row of rows) {
      const knowledgeBaseId = accessibleKnowledgeBaseIds().find((id) => knowledgeBaseName(id) === row.knowledgeBase);
      const documents = knowledgeBaseId === undefined ? [] : documentsIn(knowledgeBaseId);
      const ids = row.documents.map((name) => documents.find((document) => document.filename === name)?.id);
      if (knowledgeBaseId === undefined) skipped.push({ line: row.line, reason: `知识库“${row.knowledgeBase}”不存在或无权访问` });
      else if (ids.includes(undefined)) skipped.push({ line: row.line, reason: "期望文档在这个库里找不到" });
      else {
        const draft: QuestionDraft = {
          question: row.question,
          knowledgeBaseId,
          expectation: ids.length === 0 ? "REFUSAL" : "DOCUMENTS",
          match: row.match,
          expectedDocumentIds: ids as number[],
        };
        db.questions.push(storeQuestion(Number(params.id), draft, db.nextId++, new Date().toISOString()));
      }
    }
    return HttpResponse.json({ imported: rows.length - skipped.length, skipped });
  }),
  http.get("/api/evaluation/sets/:id/estimate", ({ params }) => {
    const denied = forbidden("eval:run");
    if (denied) return denied;
    const visible = visibleQuestions(Number(params.id));
    const runnable = visible.filter((question) => !isStale(question)).length;
    return HttpResponse.json({
      questions: runnable,
      skippedStale: visible.length - runnable,
      cost: Math.round(runnable * COST_PER_QUESTION * 100) / 100,
      currency: "CNY",
    });
  }),
  http.post("/api/evaluation/sets/:id/runs", ({ params }) => {
    const denied = forbidden("eval:run");
    if (denied) return denied;
    const setId = Number(params.id);
    if (db.runs.some((run) => run.setId === setId && progress(run) < run.results.length)) {
      return problem(409, "EVALUATION_RUNNING", "这个题集正在运行，等它跑完再试");
    }
    const round = db.runs.filter((run) => run.setId === setId).length;
    const run = simulateRun(setId, currentIdentity().id, new Date().toISOString(), (question) =>
      round % 2 === 0 ? question.id % 5 !== 0 : question.id % 3 !== 0,
    );
    return HttpResponse.json(summary(run), { status: 201 });
  }),
  http.get("/api/evaluation/sets/:id/runs", ({ params }) =>
    forbidden("eval:view") ??
    HttpResponse.json(db.runs.filter((run) => run.setId === Number(params.id)).toReversed().map(summary)),
  ),
  http.get("/api/evaluation/runs/:id", ({ params }) => {
    const run = db.runs.find((candidate) => candidate.id === Number(params.id));
    return forbidden("eval:view") ?? (run ? HttpResponse.json(detail(run)) : problem(404, "NOT_FOUND", "运行记录不存在"));
  }),
  http.get("/api/evaluation/runs/:id/export", ({ params }) => {
    const run = db.runs.find((candidate) => candidate.id === Number(params.id));
    if (!run) return problem(404, "NOT_FOUND", "运行记录不存在");
    const rows = detail(run).results.map((result) => [result.question, result.knowledgeBaseName, result.passed ? "通过" : "未通过"].join(","));
    return new HttpResponse(`\uFEFF问题,知识库,结果\n${rows.join("\n")}\n`, {
      headers: { "Content-Type": "text/csv; charset=UTF-8", "Content-Disposition": `attachment; filename="eval-run-${run.id}.csv"` },
    });
  }),
];
