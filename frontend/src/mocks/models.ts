import { http, HttpResponse } from "msw";
import type { ModelChange, ModelSettings, Provider, Usage, UsageConfig } from "@/features/models/api";
import { accountName, currentIdentity, forbidden } from "./identity";
import { modelUsageHandlers, resetModelUsage } from "./model-usage";
import { paginate } from "./page";

/**
 * 模型管理的假接口（#120、D-043）。契约与后端一致：密钥只给后 4 位；保存前先测连通，不通不能保存；
 * 在界面保存过的用途以数据库为准，否则用环境变量；每次保存和恢复都记一条变更。
 *
 * 原型里模拟连不通：服务商地址含 unreachable，或模型名含 not-exist。地址后加 ?masterKey=missing 模拟没设主密钥。
 */
const ENV_PROVIDER = "环境变量（硅基流动）";

type StoredProvider = Omit<Provider, "usedBy" | "source"> & { id: number };

const state = {
  providers: [] as StoredProvider[],
  usages: [] as UsageConfig[],
  changes: [] as ModelChange[],
  nextId: 100,
};

const ENV_MODELS: Record<Usage, string> = {
  ANSWER: "deepseek-ai/DeepSeek-V3",
  HEDGE: "deepseek-ai/DeepSeek-V4.1-Flash",
  LIGHT: "Qwen/Qwen2.5-7B-Instruct",
  RERANK: "BAAI/bge-reranker-v2-m3",
};

function minutesAgo(minutes: number) {
  return new Date(Date.now() - minutes * 60_000).toISOString();
}

export function resetModels() {
  resetModelUsage();
  state.providers = [
    { id: 1, name: "DeepSeek 官方", baseUrl: "https://api.deepseek.com/v1", apiKeyLast4: "8f2a" },
    { id: 2, name: "门店机房 vLLM", baseUrl: "http://10.20.0.8:8000/v1", apiKeyLast4: null },
  ];
  state.usages = (Object.keys(ENV_MODELS) as Usage[]).map((usage) => ({
    usage,
    providerId: null,
    providerName: ENV_PROVIDER,
    model: ENV_MODELS[usage],
    source: "ENVIRONMENT",
    updatedAt: null,
    updatedBy: null,
  }));
  Object.assign(state.usages[0]!, {
    providerId: 1,
    providerName: "DeepSeek 官方",
    model: "deepseek-chat",
    source: "DATABASE",
    updatedAt: minutesAgo(60 * 26),
    updatedBy: "超级管理员",
  });
  state.changes = [
    {
      id: 1,
      usage: "ANSWER",
      before: { providerName: ENV_PROVIDER, model: ENV_MODELS.ANSWER },
      after: { providerName: "DeepSeek 官方", model: "deepseek-chat" },
      changedBy: "超级管理员",
      changedAt: minutesAgo(60 * 26),
      restoredFrom: null,
    },
  ];
  state.nextId = 100;
}
resetModels();

const problem = (status: number, code: string, detail: string) =>
  HttpResponse.json({ status, code, detail }, { status, headers: { "Content-Type": "application/problem+json" } });

function masterKeyConfigured() {
  return new URLSearchParams(globalThis.location?.search).get("masterKey") !== "missing";
}

function providerView(provider: StoredProvider): Provider {
  return {
    ...provider,
    source: "DATABASE",
    usedBy: state.usages.filter((usage) => usage.providerId === provider.id).map((usage) => usage.usage),
  };
}

function settings(): ModelSettings {
  return {
    masterKeyConfigured: masterKeyConfigured(),
    providers: [
      {
        id: null,
        name: ENV_PROVIDER,
        baseUrl: "https://api.siliconflow.cn/v1",
        apiKeyLast4: "3c9d",
        source: "ENVIRONMENT",
        usedBy: state.usages.filter((usage) => usage.providerId === null).map((usage) => usage.usage),
      },
      ...state.providers.map(providerView),
    ],
    usages: state.usages,
    embedding: { providerName: ENV_PROVIDER, model: "BAAI/bge-m3", dimensions: 1024 },
  };
}

function providerName(id: number | null) {
  return id === null ? ENV_PROVIDER : state.providers.find((provider) => provider.id === id)?.name;
}

/** 模拟一次极短的请求：地址含 unreachable 或模型名含 not-exist 时连不通。 */
function probe(providerId: number | null, model: string) {
  const provider = state.providers.find((candidate) => candidate.id === providerId);
  if (provider?.baseUrl.includes("unreachable")) {
    return { ok: false, latencyMs: null, message: "连接超时（10 秒），请检查地址和网络" };
  }
  if (model.includes("not-exist")) {
    return { ok: false, latencyMs: 420, message: `服务商返回 404：模型 ${model} 不存在` };
  }
  return { ok: true, latencyMs: 380 + Math.round(Math.random() * 400), message: "连通正常" };
}

function apply(usage: Usage, providerId: number | null, model: string, restoredFrom: number | null) {
  const current = state.usages.find((item) => item.usage === usage)!;
  const name = providerName(providerId)!;
  state.changes.unshift({
    id: state.nextId++,
    usage,
    before: { providerName: current.providerName, model: current.model },
    after: { providerName: name, model },
    changedBy: accountName(currentIdentity().id).displayName,
    changedAt: new Date().toISOString(),
    restoredFrom,
  });
  Object.assign(current, {
    providerId,
    providerName: name,
    model,
    source: "DATABASE",
    updatedAt: new Date().toISOString(),
    updatedBy: accountName(currentIdentity().id).displayName,
  });
  return current;
}

/** 各服务商的模型列表；门店机房 vLLM 模拟“不提供列表”，地址含 unreachable 的模拟连不上。 */
const AVAILABLE: Record<string, string[]> = {
  env: [
    "deepseek-ai/DeepSeek-V3",
    "deepseek-ai/DeepSeek-V4.1-Flash",
    "deepseek-ai/DeepSeek-R1",
    "Qwen/Qwen2.5-7B-Instruct",
    "Qwen/Qwen3-32B",
    "Qwen/Qwen3-235B-A22B",
    "THUDM/GLM-4-9B-0414",
    "BAAI/bge-m3",
    "BAAI/bge-reranker-v2-m3",
    "netease-youdao/bce-reranker-base_v1",
  ],
  "1": ["deepseek-chat", "deepseek-reasoner"],
};

export const modelHandlers = [
  ...modelUsageHandlers,
  http.get("/api/models/available", ({ request }) => {
    const denied = forbidden("model:update");
    if (denied) return denied;
    const providerId = new URL(request.url).searchParams.get("providerId");
    const provider = state.providers.find((candidate) => String(candidate.id) === providerId);
    if (providerId && !provider) return problem(404, "SETTINGS_NOT_FOUND", "服务商不存在");
    if (provider?.baseUrl.includes("unreachable")) {
      return problem(502, "SETTINGS_UNREACHABLE", "连不上这个服务商，请检查地址和网络");
    }
    const models = AVAILABLE[providerId ?? "env"];
    return models
      ? HttpResponse.json(models)
      : problem(502, "MODEL_UNAVAILABLE", "这个服务商没有提供模型列表，请手动填写模型名称");
  }),
  http.get("/api/models", () => forbidden("model:view") ?? HttpResponse.json(settings())),
  http.post("/api/models/test", async ({ request }) => {
    const denied = forbidden("model:test");
    if (denied) return denied;
    const { providerId, model } = (await request.json()) as { usage: Usage; providerId: number | null; model: string };
    if (providerName(providerId) === undefined) return problem(404, "SETTINGS_NOT_FOUND", "服务商不存在");
    return HttpResponse.json(probe(providerId, model.trim()));
  }),
  http.get(
    "/api/models/changes",
    ({ request }) => forbidden("model:view") ?? HttpResponse.json(paginate(state.changes, new URL(request.url))),
  ),
  http.post("/api/models/changes/:id/restore", ({ params }) => {
    const denied = forbidden("model:update");
    if (denied) return denied;
    const change = state.changes.find((candidate) => candidate.id === Number(params.id));
    if (!change) return problem(404, "SETTINGS_NOT_FOUND", "变更记录不存在");
    const providerId =
      change.after.providerName === ENV_PROVIDER
        ? null
        : (state.providers.find((provider) => provider.name === change.after.providerName)?.id ?? undefined);
    if (providerId === undefined) return problem(409, "SETTINGS_CONFLICT", "这条记录用的服务商已被删除，不能恢复");
    const result = probe(providerId, change.after.model);
    if (!result.ok) return problem(400, "SETTINGS_UNREACHABLE", `连通测试没通过，没有恢复：${result.message}`);
    return HttpResponse.json(apply(change.usage, providerId, change.after.model, change.id));
  }),
  http.put("/api/models/:usage", async ({ params, request }) => {
    const denied = forbidden("model:update");
    if (denied) return denied;
    const usage = params.usage as Usage;
    const { providerId, model } = (await request.json()) as { providerId: number | null; model: string };
    if (!model.trim()) return problem(400, "SETTINGS_INVALID", "模型名称不能为空");
    const result = probe(providerId, model.trim());
    if (!result.ok) return problem(400, "SETTINGS_UNREACHABLE", `连通测试没通过，没有保存：${result.message}`);
    return HttpResponse.json(apply(usage, providerId, model.trim(), null));
  }),
  http.post("/api/model-providers", async ({ request }) => {
    const denied = forbidden("model:update");
    if (denied) return denied;
    const input = (await request.json()) as { name: string; baseUrl: string; apiKey?: string };
    if (input.apiKey && !masterKeyConfigured()) {
      return problem(400, "SETTINGS_MASTER_KEY_MISSING", "没有设置主密钥 RAG_MASTER_KEY，不能保存密钥");
    }
    if (state.providers.some((provider) => provider.name === input.name.trim())) {
      return problem(409, "SETTINGS_CONFLICT", `已有同名服务商：${input.name.trim()}`);
    }
    const created: StoredProvider = {
      id: state.nextId++,
      name: input.name.trim(),
      baseUrl: input.baseUrl.trim(),
      apiKeyLast4: input.apiKey ? input.apiKey.slice(-4) : null,
    };
    state.providers.push(created);
    return HttpResponse.json(providerView(created), { status: 201 });
  }),
  http.patch("/api/model-providers/:id", async ({ params, request }) => {
    const denied = forbidden("model:update");
    if (denied) return denied;
    const provider = state.providers.find((candidate) => candidate.id === Number(params.id));
    if (!provider) return problem(404, "SETTINGS_NOT_FOUND", "服务商不存在");
    const input = (await request.json()) as { name: string; baseUrl: string; apiKey?: string };
    if (input.apiKey && !masterKeyConfigured()) {
      return problem(400, "SETTINGS_MASTER_KEY_MISSING", "没有设置主密钥 RAG_MASTER_KEY，不能保存密钥");
    }
    provider.name = input.name.trim();
    provider.baseUrl = input.baseUrl.trim();
    if (input.apiKey) provider.apiKeyLast4 = input.apiKey.slice(-4);
    state.usages
      .filter((usage) => usage.providerId === provider.id)
      .forEach((usage) => (usage.providerName = provider.name));
    return HttpResponse.json(providerView(provider));
  }),
  http.delete("/api/model-providers/:id", ({ params }) => {
    const denied = forbidden("model:update");
    if (denied) return denied;
    const provider = state.providers.find((candidate) => candidate.id === Number(params.id));
    if (!provider) return problem(404, "SETTINGS_NOT_FOUND", "服务商不存在");
    if (providerView(provider).usedBy.length > 0) {
      return problem(409, "SETTINGS_CONFLICT", "还有用途在用这个服务商，先换掉才能删除");
    }
    state.providers = state.providers.filter((candidate) => candidate !== provider);
    return new HttpResponse(null, { status: 204 });
  }),
];
