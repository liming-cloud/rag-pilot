import { json } from "@/lib/api";
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

export async function fetchDocuments(knowledgeBaseId: number): Promise<KnowledgeDocument[]> {
  return json(await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/documents`), "读取文档失败");
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

/** 后端同步完成解析和入库，返回的文档状态是 READY 或 FAILED（附原因）。 */
export async function uploadDocument(
  knowledgeBaseId: number,
  file: File,
  replacesDocumentId?: number,
): Promise<KnowledgeDocument> {
  const body = new FormData();
  body.append("file", file);
  const query = replacesDocumentId ? `?replaces=${replacesDocumentId}` : "";
  const response = await authFetch(`/api/knowledge-bases/${knowledgeBaseId}/documents${query}`, {
    method: "POST",
    body,
  });
  return json(response, "上传失败");
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
