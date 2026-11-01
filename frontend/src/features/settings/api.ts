import { json } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/**
 * 系统设置能在界面改的项（D-045）。品牌由部署的环境变量给默认值，问答页文案、推荐问题、拒答话术由行业包给默认值。
 */
export type SettingKey =
  | "BRAND_NAME"
  | "BRAND_TAGLINE"
  | "LOGIN_HINT"
  | "CHAT_PLACEHOLDER"
  | "CHAT_EMPTY_HINT"
  | "SUGGESTED_QUESTIONS"
  | "REFUSAL_MESSAGE";

/** 当前值来自哪里：界面保存过的以数据库为准；没保存过时用环境变量、行业包，都没有时用内核默认值。 */
export type SettingSource = "DATABASE" | "ENVIRONMENT" | "INDUSTRY_PACK" | "KERNEL";

type Common = {
  key: SettingKey;
  source: SettingSource;
  /** 恢复默认后用的值来自哪里。 */
  defaultSource: Exclude<SettingSource, "DATABASE">;
  updatedAt: string | null;
  updatedBy: string | null;
};

/** 推荐问题是一组短句，其余都是一段文字。 */
export type SettingItem =
  | (Common & { kind: "text"; value: string; defaultValue: string })
  | (Common & { kind: "list"; value: string[]; defaultValue: string[] });

export type VocabularyEntry = {
  /** 上位词，检索时追加到问题里。 */
  term: string;
  /** 下位词和同义词，问题里出现其中之一就追加上位词。 */
  members: string[];
};

/** 当前行业包里只读的部分：影响检索和回答质量，改了要跑评测，界面不能改（D-045）。 */
export type IndustryPack = {
  code: string;
  name: string;
  /** 回答提示词里的角色。 */
  answerRole: string;
  /** 回答提示词里的引用示例句。 */
  citationExample: string;
  /** 低分改写提示词里的角色。 */
  rewriteRole: string;
  /** 低分改写提示词里的行业提示。 */
  rewriteHints: string[];
  vocabulary: VocabularyEntry[];
};

export type SystemSettings = {
  industryPack: IndustryPack;
  /** 统计“一天”按哪个时区划分；部署配置，不属于行业包。 */
  statsZone: string;
  items: SettingItem[];
};

export type SettingMeta = {
  label: string;
  /** 显示在哪里，让管理员知道改了影响什么。 */
  where: string;
  group: "brand" | "chat";
  maxLength: number;
  /** 文字较长，用多行输入框。 */
  multiline?: boolean;
  /** 列表最多几条。 */
  maxItems?: number;
};

/** 长度上限与后端校验一致；品牌三项沿用 D-042 的上限。 */
export const SETTING_META: Record<SettingKey, SettingMeta> = {
  BRAND_NAME: { label: "产品名称", where: "登录页、顶栏和浏览器标题", group: "brand", maxLength: 30 },
  BRAND_TAGLINE: { label: "一句话介绍", where: "登录页产品名称下方", group: "brand", maxLength: 60 },
  LOGIN_HINT: { label: "登录提示", where: "登录按钮下方", group: "brand", maxLength: 100, multiline: true },
  CHAT_PLACEHOLDER: { label: "输入框占位", where: "问答页输入框里，还没提问时", group: "chat", maxLength: 40 },
  CHAT_EMPTY_HINT: {
    label: "空状态说明",
    where: "新会话的对话区中间",
    group: "chat",
    maxLength: 100,
    multiline: true,
  },
  SUGGESTED_QUESTIONS: {
    label: "推荐问题",
    where: "新会话的空状态下，点一下直接提问",
    group: "chat",
    maxLength: 40,
    maxItems: 4,
  },
  REFUSAL_MESSAGE: {
    label: "拒答话术",
    where: "知识库里找不到可靠依据时的回答",
    group: "chat",
    maxLength: 150,
    multiline: true,
  },
};

export const SOURCE_LABELS: Record<SettingSource, string> = {
  DATABASE: "界面配置",
  ENVIRONMENT: "环境变量",
  INDUSTRY_PACK: "行业包",
  KERNEL: "内核默认",
};

/** 保存前在界面上先查一遍，与后端规则一致：不能为空、不超过上限；推荐问题去掉空行、不能重复。 */
export function settingProblem(key: SettingKey, value: string | string[]): string | null {
  const meta = SETTING_META[key];
  if (typeof value === "string") {
    if (!value.trim()) return `${meta.label}不能为空；要用默认值请点“恢复默认”`;
    return value.trim().length > meta.maxLength ? `${meta.label}最多 ${meta.maxLength} 个字` : null;
  }
  const items = value.map((item) => item.trim());
  if (items.some((item) => !item)) return "有空的问题，填上或删掉";
  if (items.some((item) => item.length > meta.maxLength)) return `每个问题最多 ${meta.maxLength} 个字`;
  if (new Set(items).size !== items.length) return "有重复的问题";
  return items.length > (meta.maxItems ?? Infinity) ? `最多 ${meta.maxItems} 个问题` : null;
}

export async function fetchSettings(): Promise<SystemSettings> {
  return json(await authFetch("/api/settings"), "读取系统设置失败");
}

export async function saveSetting(key: SettingKey, value: string | string[]): Promise<SettingItem> {
  const response = await authFetch(`/api/settings/${key}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ value }),
  });
  return json(response, "保存失败");
}

/** 删掉界面保存的值，回到环境变量、行业包或内核的默认值。 */
export async function restoreSetting(key: SettingKey): Promise<SettingItem> {
  return json(await authFetch(`/api/settings/${key}`, { method: "DELETE" }), "恢复默认失败");
}
