import type { User } from "@/features/auth/api";
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
};

/** 后端用 RFC 9457 问题详情返回失败原因，detail 是给用户看的中文说明。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function json<T>(response: Response, fallback: string): Promise<T> {
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { detail?: string } | null;
    const detail = response.status === 403 ? "没有权限执行此操作，请联系管理员" : (problem?.detail ?? fallback);
    throw new ApiError(response.status, detail);
  }
  return (await response.json()) as T;
}

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

export async function fetchDocuments(knowledgeBaseId: number): Promise<KnowledgeDocument[]> {
  return json(await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/documents`), "读取文档失败");
}

/** 本租户全部账号，只有管理员能调用。 */
export async function fetchUsers(): Promise<User[]> {
  return json(await authFetch("/api/users"), "读取账号失败");
}

export async function fetchMembers(knowledgeBaseId: number): Promise<{ userId: number }[]> {
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

/** 后端同步完成解析和入库，返回的文档状态是 READY 或 FAILED（附原因）。 */
export async function uploadDocument(knowledgeBaseId: number, file: File): Promise<KnowledgeDocument> {
  const body = new FormData();
  body.append("file", file);
  const response = await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/documents`, { method: "POST", body });
  return json(response, "上传失败");
}
