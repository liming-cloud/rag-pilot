import { http, HttpResponse } from "msw";
import type { KnowledgeBase, Validity } from "@/features/knowledge/api";
import { accountName, currentIdentity, forbidden } from "./identity";
import {
  sampleDocuments,
  sampleKnowledgeBases,
  type StoredDocument,
  validityOf,
  WARNING_DAYS,
  withValidity,
} from "./knowledge-samples";
import { matches, paginate, problem } from "./page";

/** 知识库、文档与授权的假接口（D-017、D-038、D-039）。 */
/** 假接口的可变数据；测试之间用 resetMockData 还原。 */
let knowledgeBases: KnowledgeBase[] = [];
let documents: Record<number, StoredDocument[]> = {};
let members: Record<number, number[]> = {};
/** 知识库授权给了哪些角色（D-038）。 */
let roleGrants: Record<number, number[]> = {};
const pendingChunks = new Map<number, number>();
let nextId = 100;

export function resetKnowledge() {
  knowledgeBases = sampleKnowledgeBases.map((knowledgeBase) => ({ ...knowledgeBase }));
  documents = Object.fromEntries(Object.entries(sampleDocuments).map(([id, list]) => [id, [...list]]));
  members = Object.fromEntries(sampleKnowledgeBases.map((knowledgeBase) => [knowledgeBase.id, [1]]));
  members[1] = [1, 2];
  roleGrants = { 1: [4] };
}

/**
 * 从 multipart 请求体里读出文件名、大小和其他表单字段。不用 request.formData()：Node 24 在 jsdom 测试环境里解析时，
 * 会用 jsdom 的 File 构造对象而触发 undici 的断言。
 */
async function uploadedFile(request: Request) {
  const body = await request.text();
  const boundary = /boundary=(.+)$/.exec(request.headers.get("Content-Type") ?? "")?.[1] ?? "";
  const parts = body.split(`--${boundary}`).filter((part) => part.includes("\r\n\r\n"));
  const fields: Record<string, string> = {};
  let name = "";
  let size = 0;
  for (const part of parts) {
    const [head, ...rest] = part.split("\r\n\r\n");
    const content = rest.join("\r\n\r\n").replace(/\r\n$/, "");
    const filename = /filename="([^"]*)"/.exec(head!)?.[1];
    if (filename !== undefined) {
      name = filename;
      size = new TextEncoder().encode(content).length;
    } else {
      fields[/name="([^"]*)"/.exec(head!)?.[1] ?? ""] = content;
    }
  }
  return { name, size, fields };
}

/** 当前账号能不能访问这个知识库：直接授权，或所属角色被授权（D-038）。 */
export function canAccess(knowledgeBaseId: number) {
  const { id, roleIds } = currentIdentity();
  return (
    (members[knowledgeBaseId]?.includes(id) ?? false) ||
    (roleGrants[knowledgeBaseId] ?? []).some((roleId) => roleIds.includes(roleId))
  );
}

export const knowledgeBaseName = (id: number) =>
  knowledgeBases.find((knowledgeBase) => knowledgeBase.id === id)?.name ?? `知识库 ${id}`;

export const accessibleKnowledgeBaseIds = () =>
  knowledgeBases.filter((knowledgeBase) => canAccess(knowledgeBase.id)).map((knowledgeBase) => knowledgeBase.id);

async function upload(knowledgeBaseId: number, request: Request, replacesDocumentId: number | null) {
  const file = await uploadedFile(request);
  if (!/\.(txt|md|markdown|pdf|docx|xlsx|xls|pptx)$/i.test(file.name)) {
    return problem(
      415,
      "KNOWLEDGE_UNSUPPORTED_TYPE",
      "目前支持 PDF、Word（.docx）、Excel（.xlsx / .xls）、PowerPoint（.pptx）、TXT 和 Markdown；旧版 .doc、.ppt 请先另存为 .docx、.pptx",
    );
  }
  const list = (documents[knowledgeBaseId] ??= []);
  if (list.some((document) => document.filename === file.name)) {
    return problem(409, "KNOWLEDGE_CONFLICT", `这份文件已经在知识库里了：${file.name}`);
  }
  // 上传新版本时没给有效期，就继承旧版本的失效日期（#124）。
  const previous = list.find((document) => document.id === replacesDocumentId);
  const given = "expiresOn" in file.fields;
  const range = {
    effectiveFrom: given ? file.fields.effectiveFrom || null : null,
    expiresOn: given ? file.fields.expiresOn || null : (previous?.expiresOn ?? null),
  };
  const invalid = invalidRange(range);
  if (invalid) return invalid;
  const created: StoredDocument = {
    id: nextId++,
    filename: file.name,
    status: "PARSING",
    error: null,
    chunkCount: 0,
    sizeBytes: file.size,
    createdAt: new Date().toISOString(),
    replacesDocumentId,
    replacesFilename: null,
    reprocessable: false,
    retryAt: null,
    ...range,
  };
  list.unshift(created);
  pendingChunks.set(created.id, Math.max(1, Math.ceil(file.size / 1_500)));
  return HttpResponse.json(withValidity(created), { status: 201 });
}

function invalidRange(range: { effectiveFrom: string | null; expiresOn: string | null }) {
  return range.effectiveFrom && range.expiresOn && range.expiresOn < range.effectiveFrom
    ? problem(400, "KNOWLEDGE_INVALID", "失效日期不能早于生效日期")
    : null;
}

export const knowledgeHandlers = [
  // 与后端一致：只返回当前账号是成员的知识库，管理员也一样（D-017）。
  // 管理端列表：要放在 /:id 之前，免得 page 被当成 id。
  http.get("/api/knowledge-bases/page", ({ request }) => {
    const url = new URL(request.url);
    const keyword = url.searchParams.get("keyword");
    const validity = url.searchParams.get("validity");
    const count = (id: number, state: Validity) =>
      (documents[id] ?? []).filter((document) => validityOf(document) === state).length;
    return HttpResponse.json(
      paginate(
        knowledgeBases
          .filter((knowledgeBase) => canAccess(knowledgeBase.id) && matches(keyword, knowledgeBase.name))
          .map((knowledgeBase) => ({
            ...knowledgeBase,
            documentCount: (documents[knowledgeBase.id] ?? []).length,
            failedCount: (documents[knowledgeBase.id] ?? []).filter((document) => document.status === "FAILED").length,
            roleGrantCount: (roleGrants[knowledgeBase.id] ?? []).length,
            memberCount: (members[knowledgeBase.id] ?? []).length,
            expiringCount: count(knowledgeBase.id, "EXPIRING"),
            expiredCount: count(knowledgeBase.id, "EXPIRED"),
          }))
          .filter(
            (summary) => !validity || (validity === "EXPIRING" ? summary.expiringCount > 0 : summary.expiredCount > 0),
          ),
        url,
      ),
    );
  }),
  // 运营概览的到期提醒：只算自己能访问的库，与列表口径一致。
  http.get("/api/knowledge-bases/expiry-summary", () => {
    const visible = knowledgeBases
      .filter((knowledgeBase) => canAccess(knowledgeBase.id))
      .flatMap((knowledgeBase) =>
        (documents[knowledgeBase.id] ?? []).map((document) => ({
          knowledgeBase,
          document,
          state: validityOf(document),
        })),
      );
    const flagged = visible
      .filter(({ state }) => state === "EXPIRING" || state === "EXPIRED")
      .sort((a, b) => a.document.expiresOn!.localeCompare(b.document.expiresOn!));
    return HttpResponse.json({
      expiring: flagged.filter(({ state }) => state === "EXPIRING").length,
      expired: flagged.filter(({ state }) => state === "EXPIRED").length,
      warningDays: WARNING_DAYS,
      documents: flagged.slice(0, 20).map(({ knowledgeBase, document, state }) => ({
        knowledgeBaseId: knowledgeBase.id,
        knowledgeBaseName: knowledgeBase.name,
        documentId: document.id,
        filename: document.filename,
        expiresOn: document.expiresOn,
        validity: state,
      })),
    });
  }),
  http.get("/api/knowledge-bases/:id", ({ params }) => {
    const knowledgeBase = knowledgeBases.find((candidate) => candidate.id === Number(params.id));
    return knowledgeBase && canAccess(knowledgeBase.id)
      ? HttpResponse.json(knowledgeBase)
      : problem(404, "KNOWLEDGE_NOT_FOUND", "知识库不存在");
  }),
  http.get("/api/knowledge-bases", () =>
    HttpResponse.json(knowledgeBases.filter((knowledgeBase) => canAccess(knowledgeBase.id))),
  ),
  http.post("/api/knowledge-bases", async ({ request }) => {
    const { name } = (await request.json()) as { name: string };
    if (knowledgeBases.some((knowledgeBase) => knowledgeBase.name === name)) {
      return problem(409, "KNOWLEDGE_CONFLICT", `已有同名知识库：${name}`);
    }
    const created = { id: nextId++, name };
    knowledgeBases.push(created);
    // 建库的人自动成为成员（D-017）。
    members[created.id] = [currentIdentity().id];
    return HttpResponse.json(created, { status: 201 });
  }),
  http.get("/api/knowledge-bases/:id/members", ({ params }) =>
    HttpResponse.json((members[Number(params.id)] ?? []).map((userId) => ({ userId, ...accountName(userId) }))),
  ),
  http.put("/api/knowledge-bases/:id/members/:userId", ({ params }) => {
    const list = (members[Number(params.id)] ??= []);
    if (!list.includes(Number(params.userId))) {
      list.push(Number(params.userId));
    }
    return new HttpResponse(null, { status: 204 });
  }),
  http.delete("/api/knowledge-bases/:id/members/:userId", ({ params }) => {
    members[Number(params.id)] = (members[Number(params.id)] ?? []).filter((id) => id !== Number(params.userId));
    return new HttpResponse(null, { status: 204 });
  }),
  http.get("/api/knowledge-bases/:id/role-grants", ({ params }) =>
    HttpResponse.json((roleGrants[Number(params.id)] ?? []).map((roleId) => ({ roleId }))),
  ),
  http.put("/api/knowledge-bases/:id/role-grants/:roleId", ({ params }) => {
    const list = (roleGrants[Number(params.id)] ??= []);
    if (!list.includes(Number(params.roleId))) list.push(Number(params.roleId));
    return new HttpResponse(null, { status: 204 });
  }),
  http.delete("/api/knowledge-bases/:id/role-grants/:roleId", ({ params }) => {
    roleGrants[Number(params.id)] = (roleGrants[Number(params.id)] ?? []).filter((id) => id !== Number(params.roleId));
    return new HttpResponse(null, { status: 204 });
  }),
  // 模拟后台入库：解析中的文档被查询一次之后变成可用；它若是新版本，旧版本随之删除。
  http.get("/api/knowledge-bases/:id/documents", ({ params, request }) => {
    const id = Number(params.id);
    const list = documents[id] ?? [];
    const url = new URL(request.url);
    const keyword = url.searchParams.get("keyword");
    const status = url.searchParams.get("status");
    const validity = url.searchParams.get("validity");
    const snapshot = paginate(
      list
        .filter((document) => matches(keyword, document.filename))
        .filter((document) => !status || document.status === status)
        .filter((document) => !validity || validityOf(document) === validity)
        .map((document) => ({
          ...withValidity(document),
          replacesFilename: list.find((other) => other.id === document.replacesDocumentId)?.filename ?? null,
        })),
      url,
    );
    for (const document of list.filter((candidate) => candidate.status === "PARSING")) {
      Object.assign(document, { status: "READY", chunkCount: pendingChunks.get(document.id) ?? 1 });
      if (document.replacesDocumentId) {
        documents[id] = (documents[id] ?? []).filter((candidate) => candidate.id !== document.replacesDocumentId);
        document.replacesDocumentId = null;
      }
    }
    return HttpResponse.json(snapshot);
  }),
  http.post("/api/knowledge-bases/:id/documents/:documentId/reprocess", ({ params }) => {
    const document = (documents[Number(params.id)] ?? []).find((item) => item.id === Number(params.documentId));
    if (!document) return HttpResponse.json({ status: 404, code: "KNOWLEDGE_NOT_FOUND", detail: "文档不存在" }, { status: 404 });
    if (document.status !== "FAILED" || !document.reprocessable) {
      return HttpResponse.json(
        {
          status: 409,
          code: "KNOWLEDGE_CONFLICT",
          detail: "只有因模型暂时不可用而失败的文档才能重新处理；文件本身有问题时请改好后重新上传",
        },
        { status: 409 },
      );
    }
    Object.assign(document, { status: "PARSING", error: null, reprocessable: false, retryAt: null });
    pendingChunks.set(document.id, 6);
    return HttpResponse.json(withValidity(document));
  }),
  http.delete("/api/knowledge-bases/:id/documents/:documentId", ({ params }) => {
    const id = Number(params.id);
    const before = documents[id]?.length ?? 0;
    documents[id] = (documents[id] ?? []).filter((document) => document.id !== Number(params.documentId));
    return documents[id].length < before
      ? new HttpResponse(null, { status: 204 })
      : problem(404, "KNOWLEDGE_NOT_FOUND", "文档不存在");
  }),
  http.put("/api/knowledge-bases/:id/documents/:documentId/validity", async ({ params, request }) => {
    const denied = forbidden("doc:update");
    if (denied) return denied;
    const document = documents[Number(params.id)]?.find((candidate) => candidate.id === Number(params.documentId));
    if (!document) return problem(404, "KNOWLEDGE_NOT_FOUND", "文档不存在");
    const range = (await request.json()) as { effectiveFrom: string | null; expiresOn: string | null };
    const invalid = invalidRange(range);
    if (invalid) return invalid;
    Object.assign(document, { effectiveFrom: range.effectiveFrom || null, expiresOn: range.expiresOn || null });
    return HttpResponse.json(withValidity(document));
  }),
  // 与后端一致：只收 PDF、Word、Excel、TXT、Markdown，同名文件视为重复。
  http.post("/api/knowledge-bases/:id/documents", ({ params, request }) => upload(Number(params.id), request, null)),
  // 上传新版本是单独的接口（doc:replace），与后端一致。
  http.post("/api/knowledge-bases/:id/documents/:documentId/versions", ({ params, request }) =>
    upload(Number(params.id), request, Number(params.documentId)),
  ),
];
