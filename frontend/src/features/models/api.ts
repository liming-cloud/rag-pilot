import { json, type Page, query } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 界面上能配的用途（D-043）：回答、备用（首字慢时的备用请求）、轻量任务（会话标题）、重排。嵌入只读。 */
export type Usage = "ANSWER" | "HEDGE" | "LIGHT" | "RERANK";

/** 配置来自哪里：在界面保存过就以数据库为准，没保存过用环境变量。 */
export type Source = "DATABASE" | "ENVIRONMENT";

/** 模型服务商（OpenAI 兼容接口）。密钥不返回明文，只给后 4 位。环境变量里的那一个也列出来，但不能改。 */
export type Provider = {
  /** 环境变量里的服务商为 null。 */
  id: number | null;
  name: string;
  baseUrl: string;
  apiKeyLast4: string | null;
  source: Source;
  /** 正在被哪些用途使用；还在用的服务商不能删。 */
  usedBy: Usage[];
};

export type UsageConfig = {
  usage: Usage;
  providerId: number | null;
  providerName: string;
  model: string;
  source: Source;
  updatedAt: string | null;
  updatedBy: string | null;
};

/** 模型管理页的全部状态。 */
export type ModelSettings = {
  /** 没设主密钥（RAG_MASTER_KEY）时不能在界面保存密钥。 */
  masterKeyConfigured: boolean;
  providers: Provider[];
  usages: UsageConfig[];
  /** 嵌入模型只显示：换嵌入要重建索引（1.x）。 */
  embedding: { providerName: string; model: string; dimensions: number };
};

export type TestResult = { ok: boolean; latencyMs: number | null; message: string };

/** 一条变更：谁、什么时候、把哪个用途从什么换成什么。 */
export type ModelChange = {
  id: number;
  usage: Usage;
  before: { providerName: string; model: string } | null;
  after: { providerName: string; model: string };
  changedBy: string;
  changedAt: string;
  /** 这条是恢复操作时，恢复的是哪条记录。 */
  restoredFrom: number | null;
};

export const PURPOSE_NAMES: Record<UsagePurpose, string> = {
  ANSWER: "回答",
  HEDGE: "备用回答",
  LIGHT: "轻量任务",
  RERANK: "重排",
  EMBEDDING: "入库向量化",
};

export const USAGE_LABELS: Record<Usage, { name: string; description: string }> = {
  ANSWER: { name: "回答", description: "生成回答；低分改写和追问检索词也用它" },
  HEDGE: { name: "备用回答", description: "回答迟迟不出第一个字时，另发一个请求，用先出字的那个" },
  LIGHT: { name: "轻量任务", description: "会话标题等不影响回答的小任务" },
  RERANK: { name: "重排", description: "给检索到的资料打相关度分" },
};

export async function fetchModelSettings(): Promise<ModelSettings> {
  return json(await authFetch("/api/models"), "读取模型配置失败");
}

/**
 * 服务商提供的模型（OpenAI 兼容接口的 /models），用来在弹窗里选，不用手填；providerId 为 null 是环境变量里的服务商。
 * 服务商不提供列表时返回错误，界面提示手动填写。
 */
export async function fetchAvailableModels(providerId: number | null): Promise<string[]> {
  return json(await authFetch(`/api/models/available${query({ providerId })}`), "这个服务商没有提供模型列表");
}

/** 保存前先测：对该用途发一句极短的请求，10 秒超时（D-043）。 */
export async function testModel(usage: Usage, providerId: number | null, model: string): Promise<TestResult> {
  const response = await authFetch("/api/models/test", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ usage, providerId, model }),
  });
  return json(response, "测试连通失败");
}

/** 保存一个用途的模型；后端会再测一次，不通就拒绝。 */
export async function saveUsage(usage: Usage, providerId: number | null, model: string): Promise<UsageConfig> {
  const response = await authFetch(`/api/models/${usage}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ providerId, model }),
  });
  return json(response, "保存失败");
}

export type ProviderInput = { name: string; baseUrl: string; apiKey?: string };

export async function createProvider(input: ProviderInput): Promise<Provider> {
  const response = await authFetch("/api/model-providers", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  return json(response, "添加服务商失败");
}

/** apiKey 留空表示不改密钥。 */
export async function updateProvider(id: number, input: ProviderInput): Promise<Provider> {
  const response = await authFetch(`/api/model-providers/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  return json(response, "保存服务商失败");
}

export async function deleteProvider(id: number): Promise<void> {
  const response = await authFetch(`/api/model-providers/${id}`, { method: "DELETE" });
  if (!response.ok) await json(response, "删除服务商失败");
}

export async function fetchModelChanges(filter: { page?: number; size?: number }): Promise<Page<ModelChange>> {
  return json(await authFetch(`/api/models/changes${query(filter)}`), "读取变更记录失败");
}

/** 恢复到某条记录之后的配置；同样先测连通，不通就拒绝。 */
export async function restoreChange(id: number): Promise<UsageConfig> {
  return json(await authFetch(`/api/models/changes/${id}/restore`, { method: "POST" }), "恢复失败");
}

/** 用量统计里的用途：四个可配用途加入库向量化（嵌入不能在界面配置）。 */
export type UsagePurpose = Usage | "EMBEDDING";

export type UsageGroupBy = "DAY" | "MONTH" | "PURPOSE" | "MODEL";

/** 统计表的一行。key 随分组而变：日期、月份、用途名、“模型 · 服务商域名”。cost 按当前单价估算（元）；unpriced 表示有用量没填单价，费用偏低。 */
export type UsageGroup = {
  key: string;
  calls: number;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  unpriced: boolean;
};

/** 一个模型每百万 token 的单价（元）；没填过为 null。 */
export type ModelPrice = { model: string; inputPerMillion: number | null; outputPerMillion: number | null };

export type UsageReport = {
  from: string;
  to: string;
  groupBy: UsageGroupBy;
  groups: UsageGroup[];
  total: UsageGroup;
  /** 有用量或填过单价的模型。 */
  prices: ModelPrice[];
};

export type UsageFilter = { groupBy: UsageGroupBy; from?: string };

/** 不传 from 看最近 30 天。 */
export async function fetchUsageReport(filter: UsageFilter): Promise<UsageReport> {
  return json(await authFetch(`/api/models/usage${query(filter)}`), "读取用量失败");
}

/** 填或改一个模型的单价（元 / 百万 token）。 */
export async function saveModelPrice(price: Required<ModelPrice>): Promise<ModelPrice> {
  const response = await authFetch("/api/models/prices", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(price),
  });
  return json(response, "保存单价失败");
}

/** 模型管理的查询键；changes、available、usage 是前缀。 */
export const modelKeys = {
  settings: ["model-settings"],
  changes: ["model-changes"],
  changePage: (filter: Parameters<typeof fetchModelChanges>[0]) => ["model-changes", filter] as const,
  available: ["available-models"],
  availableFor: (providerId: string) => ["available-models", providerId] as const,
  usage: ["model-usage"],
  usageReport: (filter: UsageFilter) => ["model-usage", filter] as const,
} as const;
