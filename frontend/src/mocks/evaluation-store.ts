import type {
  Change,
  EvalSet,
  Expectation,
  GoldenQuestion,
  MatchMode,
  QuestionResult,
  RunDetail,
  RunSummary,
} from "@/features/evaluation/api";
import { accountName } from "./identity";
import { canAccess, documentsIn, knowledgeBaseName } from "./knowledge";

/**
 * 知识质量假接口的数据和计算（#224），处理器在 evaluation.ts。与后端一致：每道题在一个知识库里检索，当前账号无权访问这个库的题当作不存在——
 * 列表、计数、运行、结果和导出都不含它。运行在“后台”逐题进行，每 400 毫秒完成一道，原型里能看到进度。
 */
export type StoredSet = { id: number; name: string; description: string; updatedAt: string };
export type StoredQuestion = {
  id: number;
  setId: number;
  question: string;
  knowledgeBaseId: number;
  expectation: Expectation;
  match: MatchMode;
  expected: { documentId: number; filename: string }[];
  createdAt: string;
};
type StoredResult = { questionId: number; passed: boolean; refused: boolean; recalled: number[]; cited: number[]; answerText: string };
type StoredRun = { id: number; setId: number; runnerId: number; startedAt: string; results: StoredResult[] };

export const STEP_MS = 400;
export const COST_PER_QUESTION = 0.012;

/** 可变的假数据；处理器在 evaluation.ts。 */
export const db = { sets: [] as StoredSet[], questions: [] as StoredQuestion[], runs: [] as StoredRun[], nextId: 1000 };

export const ago = (days: number) => new Date(Date.now() - days * 86_400_000).toISOString();

const SAMPLES: [string, number, number[], MatchMode?][] = [
  ["临期商品打几折？", 1, [11]],
  ["报损要谁签字？", 1, [11]],
  ["夜班交接要核对哪些东西？", 1, [17]],
  ["收银机卡住了怎么重启？", 1, [18]],
  ["会员积分多久清零？", 1, [15]],
  ["双十一当天的排班怎么安排？", 1, [14, 20], "ALL"],
  ["促销期间临期品还能参加满减吗？", 1, [11, 14], "ANY"],
  ["新员工第一周要学哪些课程？", 1, [19]],
  ["交接班时现金差异怎么处理？", 1, [16, 21], "ANY"],
  ["陈列手册里端架怎么摆？", 1, [12]],
  ["临期与报损在商品库里怎么规定？", 2, [201]],
  ["门店能代收快递吗？", 1, []],
  ["公司股票什么时候分红？", 1, []],
  ["区域 1 的门店几点开门？", 3, []],
  ["旧版收银流程第三步是什么？", 1, [999]],
];

export function resetEvaluation() {
  db.sets = [
    { id: 1, name: "门店运营回归", description: "换模型、整理资料后跑一遍，看门店常见问题有没有变差", updatedAt: ago(2) },
    { id: 2, name: "新员工入职", description: "", updatedAt: ago(10) },
  ];
  db.questions = SAMPLES.map(([question, knowledgeBaseId, documentIds, match], index) => ({
    id: index + 1,
    setId: 1,
    question,
    knowledgeBaseId,
    expectation: documentIds.length === 0 ? "REFUSAL" : "DOCUMENTS",
    match: match ?? "ALL",
    expected: documentIds.map((documentId) => ({
      documentId,
      filename: documentId === 999 ? "收银流程（旧版）.md" : (documentsIn(knowledgeBaseId).find((d) => d.id === documentId)?.filename ?? ""),
    })),
    createdAt: ago(20),
  }));
  db.questions.push({ ...db.questions[7]!, id: 101, setId: 2, question: "入职第一天要带什么材料？" });
  db.runs = [];
  db.nextId = 1000;
  simulateRun(1, 1, ago(3), (question) => question.id % 4 !== 0);
  simulateRun(1, 1, ago(1), (question) => question.id % 5 !== 0);
}

export function deleted(question: StoredQuestion, documentId: number) {
  return !documentsIn(question.knowledgeBaseId).some((document) => document.id === documentId);
}

export const isStale = (question: StoredQuestion) => question.expected.some((document) => deleted(question, document.documentId));
export const visibleQuestions = (setId: number) =>
  db.questions.filter((question) => question.setId === setId && canAccess(question.knowledgeBaseId));

export function questionView(question: StoredQuestion): GoldenQuestion {
  return {
    id: question.id,
    question: question.question,
    knowledgeBaseId: question.knowledgeBaseId,
    knowledgeBaseName: knowledgeBaseName(question.knowledgeBaseId),
    expectation: question.expectation,
    match: question.match,
    expectedDocuments: question.expected.map((document) => ({ ...document, deleted: deleted(question, document.documentId) })),
    stale: isStale(question),
    createdAt: question.createdAt,
  };
}

/** 按规则造出每道题的结果；passes 决定这次通过哪些题，未通过的题按期望类型给出对应的信号。 */
export function simulateRun(setId: number, runnerId: number, startedAt: string, passes: (question: StoredQuestion) => boolean) {
  const results = db.questions
    .filter((question) => question.setId === setId && canAccessAs(runnerId, question) && !isStale(question))
    .map((question): StoredResult => {
      const passed = passes(question);
      const ids = question.expected.map((document) => document.documentId);
      if (question.expectation === "REFUSAL") {
        return passed
          ? { questionId: question.id, passed, refused: true, recalled: [], cited: [], answerText: "资料里没有找到相关内容。" }
          : { questionId: question.id, passed, refused: false, recalled: [11], cited: [11], answerText: "可以，门店按临期管理规定处理。" };
      }
      return passed
        ? { questionId: question.id, passed, refused: false, recalled: ids, cited: ids, answerText: `根据资料，${question.question.replace(/？$/, "")}的规定如下……` }
        : { questionId: question.id, passed, refused: true, recalled: ids.slice(1), cited: [], answerText: "资料里没有找到相关内容。" };
    });
  const run = { id: db.nextId++, setId, runnerId, startedAt, results };
  db.runs.push(run);
  return run;
}

/** 原型里运行人就是当前账号或管理员（管理员是所有库的成员）。 */
const canAccessAs = (runnerId: number, question: StoredQuestion) => runnerId === 1 || canAccess(question.knowledgeBaseId);

export function progress(run: StoredRun) {
  const elapsed = Date.now() - new Date(run.startedAt).getTime();
  return Math.min(run.results.length, Math.floor(elapsed / STEP_MS));
}

/** 只看当前账号能访问的题；对比同一题集的上一次运行。 */
export function detail(run: StoredRun): RunDetail {
  const done = progress(run);
  const finished = done === run.results.length;
  const visible = run.results.slice(0, done).flatMap((result) => {
    const question = db.questions.find((candidate) => candidate.id === result.questionId);
    return question && canAccess(question.knowledgeBaseId) ? [{ result, question }] : [];
  });
  const previous = db.runs.filter((other) => other.setId === run.setId && other.id < run.id).at(-1);
  const results = visible.map(({ result, question }): QuestionResult => {
    const before = previous?.results.find((other) => other.questionId === result.questionId);
    const change: Change = !before ? "NEW" : before.passed === result.passed ? "SAME" : result.passed ? "NEWLY_PASSED" : "NEWLY_FAILED";
    const name = (id: number) => question.expected.find((document) => document.documentId === id)?.filename ?? `文档 ${id}`;
    const expectedIds = question.expected.map((document) => document.documentId);
    return {
      questionId: question.id,
      question: question.question,
      knowledgeBaseName: knowledgeBaseName(question.knowledgeBaseId),
      expectation: question.expectation,
      match: question.match,
      passed: result.passed,
      change,
      refused: result.refused,
      expectedCount: expectedIds.length,
      recalledCount: result.recalled.filter((id) => expectedIds.includes(id)).length,
      citedCount: result.cited.filter((id) => expectedIds.includes(id)).length,
      missingDocuments: expectedIds.filter((id) => !result.recalled.includes(id)).map(name),
      answerText: result.answerText,
      citedDocuments: result.cited.map((id) => documentsIn(question.knowledgeBaseId).find((d) => d.id === id)?.filename ?? name(id)),
    };
  });
  const set = db.sets.find((candidate) => candidate.id === run.setId)!;
  return {
    id: run.id,
    setId: set.id,
    setName: set.name,
    status: finished ? "DONE" : "RUNNING",
    total: run.results.filter((result) => visibleById(result.questionId)).length,
    done: results.length,
    passed: results.filter((result) => result.passed).length,
    failed: results.filter((result) => !result.passed).length,
    newlyFailed: results.filter((result) => result.change === "NEWLY_FAILED").length,
    newlyPassed: results.filter((result) => result.change === "NEWLY_PASSED").length,
    runnerName: accountName(run.runnerId).displayName,
    startedAt: run.startedAt,
    finishedAt: finished ? new Date(new Date(run.startedAt).getTime() + run.results.length * STEP_MS).toISOString() : null,
    results,
  };
}

const visibleById = (questionId: number) => {
  const question = db.questions.find((candidate) => candidate.id === questionId);
  return question !== undefined && canAccess(question.knowledgeBaseId);
};

export function summary(run: StoredRun): RunSummary {
  const { id, status, total, done, passed, failed, newlyFailed, newlyPassed, runnerName, startedAt, finishedAt } = detail(run);
  return { id, status, total, done, passed, failed, newlyFailed, newlyPassed, runnerName, startedAt, finishedAt };
}

export function setView(set: StoredSet): EvalSet {
  const visible = visibleQuestions(set.id);
  const last = db.runs.filter((run) => run.setId === set.id).at(-1);
  return {
    ...set,
    questionCount: visible.length,
    staleCount: visible.filter(isStale).length,
    knowledgeBaseNames: [...new Set(visible.map((question) => knowledgeBaseName(question.knowledgeBaseId)))],
    lastRun: last ? summary(last) : null,
  };
}

