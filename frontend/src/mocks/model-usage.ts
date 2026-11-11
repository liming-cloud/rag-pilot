import { http, HttpResponse } from "msw";
import type { ModelPrice, UsageGroup, UsageGroupBy, UsageReport } from "@/features/models/api";
import { forbidden } from "./identity";
import { problem } from "./page";

/** 模型用量与单价的假接口（#169、D-065）。 */
/** 用量的日聚合行：和后端 model_usage_daily 一致，只有数字，没有问题原文和提问的人。 */
type UsageRow = { day: string; purpose: string; provider: string; model: string; calls: number; input: number; output: number };

const usage = { rows: [] as UsageRow[], prices: [] as ModelPrice[] };

/** 最近 60 天的假用量：回答和重排天天有，轻量任务隔天有，入库向量化只在周一（批量上传）有。 */
function seedUsage(): UsageRow[] {
  const rows: UsageRow[] = [];
  for (let ago = 0; ago < 60; ago++) {
    const date = new Date(Date.now() - ago * 86_400_000);
    const day = date.toISOString().slice(0, 10);
    const load = 20 + ((ago * 37) % 23);
    rows.push(
      { day, purpose: "ANSWER", provider: "api.deepseek.com", model: "deepseek-chat", calls: load, input: load * 2600, output: load * 310 },
      { day, purpose: "RERANK", provider: "api.siliconflow.cn", model: "BAAI/bge-reranker-v2-m3", calls: load, input: load * 1800, output: 0 },
    );
    if (ago % 2 === 0) {
      rows.push({ day, purpose: "LIGHT", provider: "api.siliconflow.cn", model: "Qwen/Qwen2.5-7B-Instruct", calls: 6, input: 900, output: 120 });
    }
    if (date.getUTCDay() === 1) {
      rows.push({ day, purpose: "EMBEDDING", provider: "api.siliconflow.cn", model: "BAAI/bge-m3", calls: 14, input: 380_000, output: 0 });
    }
  }
  return rows;
}

function usageReport(url: URL): UsageReport {
  const to = new Date().toISOString().slice(0, 10);
  const from = url.searchParams.get("from") ?? new Date(Date.now() - 29 * 86_400_000).toISOString().slice(0, 10);
  const groupBy = (url.searchParams.get("groupBy") ?? "DETAIL") as UsageGroupBy;
  const key = (row: UsageRow) =>
    ({ DETAIL: `${row.day} ${row.purpose} ${row.provider} ${row.model}`, DAY: row.day, MONTH: row.day.slice(0, 7), PURPOSE: row.purpose, MODEL: `${row.model} · ${row.provider}` })[groupBy];
  const empty = (groupKey: string, row?: UsageRow): UsageGroup => ({
    key: row ? row.day : groupKey,
    purpose: row ? (row.purpose as UsageGroup["purpose"]) : null,
    provider: row?.provider ?? null,
    model: row?.model ?? null,
    calls: 0,
    inputTokens: 0,
    outputTokens: 0,
    cost: 0,
    unpriced: false,
  });
  const groups = new Map<string, UsageGroup>();
  const total = empty("合计");
  for (const row of usage.rows.filter((candidate) => candidate.day >= from && candidate.day <= to)) {
    const price = usage.prices.find((candidate) => candidate.model === row.model);
    const cost = price
      ? ((price.inputPerMillion ?? 0) * row.input + (price.outputPerMillion ?? 0) * row.output) / 1e6
      : 0;
    const group = groups.get(key(row)) ?? empty(key(row), groupBy === "DETAIL" ? row : undefined);
    groups.set(key(row), group);
    for (const target of [group, total]) {
      target.calls += row.calls;
      target.inputTokens += row.input;
      target.outputTokens += row.output;
      target.cost += cost;
      target.unpriced ||= !price;
    }
  }
  const sorted = [...groups.values()].sort((a, b) =>
    groupBy === "DETAIL" || groupBy === "DAY" || groupBy === "MONTH"
      ? b.key.localeCompare(a.key) : b.inputTokens + b.outputTokens - (a.inputTokens + a.outputTokens),
  );
  const models = new Set([...usage.rows.map((row) => row.model), ...usage.prices.map((price) => price.model)]);
  const prices = [...models].sort().map((model) => usage.prices.find((price) => price.model === model) ?? { model, inputPerMillion: null, outputPerMillion: null });
  const page = Number(url.searchParams.get("page") ?? 1);
  const size = Number(url.searchParams.get("size") ?? 20);
  const groupPage = { items: sorted.slice((page - 1) * size, page * size), total: sorted.length, page, size };
  return { from, to, groupBy, groups: groupPage, total, prices };
}

export function resetModelUsage() {
  usage.rows = seedUsage();
  usage.prices = [
    { model: "deepseek-chat", inputPerMillion: 2, outputPerMillion: 8 },
    { model: "BAAI/bge-reranker-v2-m3", inputPerMillion: 0.5, outputPerMillion: 0 },
  ];
}

export const modelUsageHandlers = [
  http.get(
    "/api/models/usage",
    ({ request }) => forbidden("model:view") ?? HttpResponse.json(usageReport(new URL(request.url))),
  ),
  http.put("/api/models/prices", async ({ request }) => {
    const denied = forbidden("model:update");
    if (denied) return denied;
    const price = (await request.json()) as ModelPrice;
    if ((price.inputPerMillion ?? -1) < 0 || (price.outputPerMillion ?? -1) < 0) {
      return problem(400, "SETTINGS_INVALID", "输入单价必须是 0 到 1000000 之间的数");
    }
    usage.prices = [...usage.prices.filter((candidate) => candidate.model !== price.model), price];
    return HttpResponse.json(price);
  }),
];
