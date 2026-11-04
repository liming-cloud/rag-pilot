import { http, HttpResponse } from "msw";
import {
  type IndustryPack,
  type SettingItem,
  type SettingKey,
  type SettingSource,
  settingProblem,
  type SystemSettings,
} from "@/features/settings/api";
import { DEFAULT_BRANDING } from "@/lib/branding";
import { accountName, currentIdentity, forbidden } from "./identity";

/**
 * 系统设置的假接口（#122、D-045）。示例数据与 backend/contexts/rag-settings 的 industry-packs/retail/pack.yml 一致；契约与后端一致：界面保存过的以数据库为准，没保存过时品牌用环境变量
 * （没设时用内核默认值），问答页文案、推荐问题、拒答话术用行业包；提示词角色、改写提示、行业词表只读。
 *
 * 地址后加 ?brand=retail 模拟部署时用环境变量设了新零售的品牌，加 ?brand=default 换回内核默认；选择记在
 * sessionStorage，刷新后不丢。
 */
export const RETAIL: IndustryPack = {
  code: "retail",
  name: "新零售",
  answerRole: "你是连锁门店的运营助手，回答门店员工的问题。",
  citationExample: "临期商品提前三天下架[2]。",
  rewriteRole: "你负责把连锁门店员工的提问改写成便于检索门店制度文件的规范问题。",
  rewriteHints: ["提到具体商品时，在后面补上它所属的商品类别。"],
  vocabulary: [
    { term: "鲜食", members: ["便当", "饭团", "三明治", "寿司", "关东煮", "凉面"] },
    { term: "日配", members: ["牛奶", "鲜奶", "酸奶", "面包"] },
    { term: "冻品", members: ["冰淇淋", "雪糕", "速冻水饺"] },
    { term: "临期商品", members: ["临期品", "快过期", "快到期"] },
    { term: "报损", members: ["报废", "坏货"] },
    { term: "收银差异", members: ["长短款"] },
    { term: "价签", members: ["标价签", "价格牌", "价格标签"] },
    { term: "交接班", members: ["交班", "接班"] },
    { term: "陈列", members: ["摆货", "排面"] },
    { term: "促销", members: ["打折", "特价", "满减"] },
  ],
};

const RETAIL_DEFAULTS = {
  CHAT_PLACEHOLDER: "问问门店规范、商品、促销…",
  CHAT_EMPTY_HINT: "问问门店规范、商品、促销，回答会标出原文出处，可以接着追问。",
  SUGGESTED_QUESTIONS: ["临期商品怎么处理？", "鲜食晚上几点下架？", "收银长短款怎么处理？", "促销价签多久检查一次？"],
  REFUSAL_MESSAGE: "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。",
};

/** 部署时用环境变量 RAG_BRAND_* 设的品牌（D-042），作品牌的默认值。 */
const ENV_BRANDINGS = {
  default: null,
  retail: {
    BRAND_NAME: "门店运营知识库",
    BRAND_TAGLINE: "门店规范、商品、促销，问了就有出处",
    LOGIN_HINT: "账号由店长或总部管理员开通；忘记密码请联系店长。",
  },
} as const;

function brandKey(): keyof typeof ENV_BRANDINGS {
  const asked = new URLSearchParams(globalThis.location?.search).get("brand");
  if (asked === "retail" || asked === "default") {
    globalThis.sessionStorage?.setItem("mock.brand", asked);
    return asked;
  }
  return globalThis.sessionStorage?.getItem("mock.brand") === "retail" ? "retail" : "default";
}

type Saved = { value: string | string[]; updatedAt: string; updatedBy: string };

const state = { saved: {} as Partial<Record<SettingKey, Saved>> };

export function resetSettings() {
  state.saved = {
    SUGGESTED_QUESTIONS: {
      value: ["临期商品怎么处理？", "便当饭团晚上几点得撤下来？", "收银长短款怎么上报？"],
      updatedAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
      updatedBy: "超级管理员",
    },
  };
}
resetSettings();

function defaultOf(key: SettingKey): { value: string | string[]; source: Exclude<SettingSource, "DATABASE"> } {
  switch (key) {
    case "BRAND_NAME":
    case "BRAND_TAGLINE":
    case "LOGIN_HINT": {
      const env = ENV_BRANDINGS[brandKey()];
      if (env) return { value: env[key], source: "ENVIRONMENT" };
      const kernel = { BRAND_NAME: "name", BRAND_TAGLINE: "tagline", LOGIN_HINT: "loginHint" } as const;
      return { value: DEFAULT_BRANDING[kernel[key]], source: "KERNEL" };
    }
    default:
      return { value: RETAIL_DEFAULTS[key], source: "INDUSTRY_PACK" };
  }
}

const KEYS: SettingKey[] = [
  "BRAND_NAME",
  "BRAND_TAGLINE",
  "LOGIN_HINT",
  "CHAT_PLACEHOLDER",
  "CHAT_EMPTY_HINT",
  "SUGGESTED_QUESTIONS",
  "REFUSAL_MESSAGE",
];

function itemOf(key: SettingKey): SettingItem {
  const fallback = defaultOf(key);
  const saved = state.saved[key];
  return {
    key,
    kind: Array.isArray(fallback.value) ? "list" : "text",
    value: saved?.value ?? fallback.value,
    defaultValue: fallback.value,
    source: saved ? "DATABASE" : fallback.source,
    defaultSource: fallback.source,
    updatedAt: saved?.updatedAt ?? null,
    updatedBy: saved?.updatedBy ?? null,
  } as SettingItem;
}

const valueOf = <T extends string | string[]>(key: SettingKey) => itemOf(key).value as T;

const problem = (status: number, code: string, detail: string) =>
  HttpResponse.json({ status, code, detail }, { status, headers: { "Content-Type": "application/problem+json" } });

const settingKey = (raw: unknown) => (KEYS.includes(raw as SettingKey) ? (raw as SettingKey) : null);

export const settingHandlers = [
  // 登录页要用，不需要登录；和登录接口一样在 /api/auth 下。
  http.get("/api/auth/branding", () =>
    HttpResponse.json({
      name: valueOf<string>("BRAND_NAME"),
      tagline: valueOf<string>("BRAND_TAGLINE"),
      loginHint: valueOf<string>("LOGIN_HINT"),
    }),
  ),
  http.get("/api/settings/chat", () =>
    HttpResponse.json({
      placeholder: valueOf<string>("CHAT_PLACEHOLDER"),
      emptyHint: valueOf<string>("CHAT_EMPTY_HINT"),
      suggestedQuestions: valueOf<string[]>("SUGGESTED_QUESTIONS"),
      // 原型（#145 A8）：答不上来时的下一步，接接口后由行业包给出。
      refusalTips: ["说出具体的商品名或场景，比如“鲜食”“冷柜”。", "把一个问题拆成几个小问题分开问。"],
      refusalContact: "还是找不到的话，请联系店长确认。",
    }),
  ),
  http.get(
    "/api/settings",
    () =>
      forbidden("setting:view") ??
      HttpResponse.json({
        industryPack: RETAIL,
        statsZone: "Asia/Shanghai",
        items: KEYS.map(itemOf),
      } satisfies SystemSettings),
  ),
  http.put("/api/settings/:key", async ({ params, request }) => {
    const denied = forbidden("setting:update");
    if (denied) return denied;
    const key = settingKey(params.key);
    if (!key) return problem(404, "SETTINGS_NOT_FOUND", "没有这项设置");
    const { value } = (await request.json()) as { value: string | string[] };
    if (Array.isArray(value) !== Array.isArray(defaultOf(key).value)) {
      return problem(400, "SETTINGS_INVALID", "设置值的格式不对");
    }
    const invalid = settingProblem(key, value);
    if (invalid) return problem(400, "SETTINGS_INVALID", invalid);
    const cleaned = Array.isArray(value) ? value.map((item) => item.trim()) : value.trim();
    state.saved[key] = {
      value: cleaned,
      updatedAt: new Date().toISOString(),
      updatedBy: accountName(currentIdentity().id).displayName,
    };
    return HttpResponse.json(itemOf(key));
  }),
  http.delete("/api/settings/:key", ({ params }) => {
    const denied = forbidden("setting:update");
    if (denied) return denied;
    const key = settingKey(params.key);
    if (!key) return problem(404, "SETTINGS_NOT_FOUND", "没有这项设置");
    delete state.saved[key];
    return HttpResponse.json(itemOf(key));
  }),
];
