import { json, type Page, query } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 与后端 KnowledgeBaseController 的响应一致。 */
export type KnowledgeBase = { id: number; name: string };

export type KnowledgeDocument = {
  id: number;
  filename: string;
  status: "PARSING" | "READY" | "FAILED";
  error: string | null;
  chunkCount: number;
  sizeBytes: number;
  createdAt: string | null;
  /** 这份文档是哪份文档的新版本；替换完成后旧版本被删，这里变回 null。 */
  replacesDocumentId: number | null;
  /** 被替换的旧版本的文件名；分页后旧版本不一定在同一页，所以由后端直接给出。 */
  replacesFilename: string | null;
  /** 生效日期（含当天），null 表示不限；日期按统计时区解释（#124）。 */
  effectiveFrom: string | null;
  /** 失效日期，当天仍有效，次日 0 点起不参与检索；null 表示长期有效。 */
  expiresOn: string | null;
  /** 后端按今天和提前提醒天数算好的状态，前端不自己比较日期（时区以后端为准）。 */
  validity: Validity;
  /** 因模型暂时不可用而失败，可以“重新处理”；文件本身有问题时为 false，要改好后重传（#145 A9）。 */
  reprocessable: boolean;
  /** 自动重试的时间（第一次失败 10 分钟后，只重试一次）；不自动重试时为 null。 */
  retryAt: string | null;
};

/** 有效期状态：有效、即将到期（提醒天数内）、已失效、未生效；后两种不参与检索和回答。 */
export type Validity = "ACTIVE" | "EXPIRING" | "EXPIRED" | "PENDING";

export const VALIDITY = {
  ACTIVE: { label: "有效", variant: "success" },
  EXPIRING: { label: "即将到期", variant: "default" },
  EXPIRED: { label: "已失效", variant: "destructive" },
  PENDING: { label: "未生效", variant: "outline" },
} as const;

/** 生效、失效日期，格式 yyyy-MM-dd，空表示不限。 */
export type ValidityRange = { effectiveFrom: string | null; expiresOn: string | null };

/** 管理端知识库列表的一行（D-039）。 */
export type KnowledgeBaseSummary = {
  id: number;
  name: string;
  documentCount: number;
  failedCount: number;
  roleGrantCount: number;
  memberCount: number;
  /** 提醒天数内到期的文档数。 */
  expiringCount: number;
  /** 已失效的文档数。 */
  expiredCount: number;
};

/** 直接授权的账号，带上名字，不用为了显示名字再读全部账号。 */
export type Member = { userId: number; username: string; displayName: string };

/** 管理端：按名称筛选、分页列出自己能访问的知识库。 */
export async function fetchKnowledgeBasePage(filter: {
  keyword?: string;
  /** 只看有即将到期或已失效文档的库。 */
  validity?: "EXPIRING" | "EXPIRED" | null;
  page?: number;
  size?: number;
}): Promise<Page<KnowledgeBaseSummary>> {
  return json(await authFetch(`/api/knowledge-bases/page${query(filter)}`), "读取知识库失败");
}

export async function fetchKnowledgeBase(id: number): Promise<KnowledgeBase> {
  return json(await authFetch(`/api/knowledge-bases/${id}`), "读取知识库失败");
}

export type DocumentFilter = {
  keyword?: string;
  status?: KnowledgeDocument["status"] | null;
  validity?: Validity | null;
  page?: number;
  size?: number;
};

export async function fetchKnowledgeBases(): Promise<KnowledgeBase[]> {
  return json(await authFetch("/api/knowledge-bases"), "读取知识库失败");
}

export async function createKnowledgeBase(name: string): Promise<KnowledgeBase> {
  const response = await authFetch("/api/knowledge-bases", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name }),
  });
  return json(response, "新建知识库失败");
}

/** 按文件名、状态筛选，分页列出文档（D-039）。 */
export async function fetchDocuments(
  knowledgeBaseId: number,
  filter: DocumentFilter = {},
): Promise<Page<KnowledgeDocument>> {
  return json(await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/documents${query(filter)}`), "读取文档失败");
}

export async function fetchMembers(knowledgeBaseId: number): Promise<Member[]> {
  return json(await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/members`), "读取成员失败");
}

/** 授予或撤销成员；成功时后端返回 204，没有响应体。 */
export async function setMember(knowledgeBaseId: number, userId: number, granted: boolean): Promise<void> {
  const response = await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/members/${userId}`, {
    method: granted ? "PUT" : "DELETE",
  });
  if (!response.ok) {
    await json(response, granted ? "授权失败" : "撤销失败");
  }
}

/** 授权给了哪些角色。属于这些角色的账号都能检索这个库（D-038）。 */
export async function fetchRoleGrants(knowledgeBaseId: number): Promise<{ roleId: number }[]> {
  return json(await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/role-grants`), "读取角色授权失败");
}

/** 授权给角色或收回；收回后属于该角色、又没有直接授权的账号立即检索不到。 */
export async function setRoleGrant(knowledgeBaseId: number, roleId: number, granted: boolean): Promise<void> {
  const response = await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/role-grants/${roleId}`, {
    method: granted ? "PUT" : "DELETE",
  });
  if (!response.ok) {
    await json(response, granted ? "授权失败" : "收回失败");
  }
}

/**
 * 上传文档或新版本，返回的文档先是解析中，后台处理完变成可用或失败（附原因）。
 * 可以顺带设有效期（doc:upload）；上传新版本时不传有效期则继承旧版本的失效日期。
 */
export async function uploadDocument(
  knowledgeBaseId: number,
  file: File,
  replacesDocumentId?: number,
  validity?: ValidityRange,
): Promise<KnowledgeDocument> {
  const body = new FormData();
  body.append("file", file);
  if (validity) {
    body.append("effectiveFrom", validity.effectiveFrom ?? "");
    body.append("expiresOn", validity.expiresOn ?? "");
  }
  // 上传新版本是单独的接口（doc:replace），新版本处理成功后旧版本自动删除。
  const path = replacesDocumentId ? `/documents/${replacesDocumentId}/versions` : "/documents";
  const response = await authFetch(`/api/knowledge-bases/${knowledgeBaseId}${path}`, {
    method: "POST",
    body,
  });
  return json(response, "上传失败");
}

/** 修改有效期（doc:update），保存后立即按新日期参与或退出检索。 */
export async function updateValidity(
  knowledgeBaseId: number,
  documentId: number,
  validity: ValidityRange,
): Promise<KnowledgeDocument> {
  const response = await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/validity`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(validity),
  });
  return json(response, "保存有效期失败");
}

/** 自己能访问的知识库里即将到期和已失效的文档数，供运营概览提醒。 */
export type ExpirySummary = {
  expiring: number;
  expired: number;
  warningDays: number;
  /** 最早到期的若干份（已失效的在前），供运营概览和周报列出（#125）。 */
  documents: {
    knowledgeBaseId: number;
    knowledgeBaseName: string;
    documentId: number;
    filename: string;
    expiresOn: string;
    validity: "EXPIRING" | "EXPIRED";
  }[];
};

export async function fetchExpirySummary(): Promise<ExpirySummary> {
  return json(await authFetch("/api/knowledge-bases/expiry-summary"), "读取资料有效期失败");
}

/** 删除文档及其分块，之后不会再被检索到。 */
export async function deleteDocument(knowledgeBaseId: number, documentId: number): Promise<void> {
  const response = await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`, {
    method: "DELETE",
  });
  if (!response.ok) {
    await json(response, "删除失败");
  }
}

/** 知识库相关的查询键；all 是前缀，让它失效时列表、详情、文档一起刷新。 */
export const knowledgeKeys = {
  all: ["knowledge-bases"],
  page: (filter: Parameters<typeof fetchKnowledgeBasePage>[0]) => ["knowledge-bases", "page", filter] as const,
  detail: (id: number) => ["knowledge-bases", id] as const,
  documents: (knowledgeBaseId: number, filter: DocumentFilter) =>
    ["knowledge-bases", knowledgeBaseId, "documents", filter] as const,
  expirySummary: ["knowledge-bases", "expiry-summary"],
  members: (knowledgeBaseId: number) => ["knowledge-bases", knowledgeBaseId, "members"] as const,
  roleGrants: (knowledgeBaseId: number) => ["knowledge-bases", knowledgeBaseId, "role-grants"] as const,
} as const;
