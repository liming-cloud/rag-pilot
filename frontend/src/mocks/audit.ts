import { http, HttpResponse } from "msw";
import type { AuditRecord } from "@/features/audit/api";
import { forbidden } from "./identity";
import { paginate } from "./page";

/** 审计记录的假接口（#145 C2）：一些近期的管理操作，按天数和操作类型筛选，新的在前，与后端一致。 */
const SAMPLES: Array<Omit<AuditRecord, "id" | "occurredAt"> & { hoursAgo: number }> = [
  { hoursAgo: 1, actorId: 1, actorName: "系统管理员", action: "KB_MEMBER_GRANTED", targetType: "KNOWLEDGE_BASE", targetId: "1", detail: "知识库“门店运营知识库”授权给账号 staff02" },
  { hoursAgo: 3, actorId: 1, actorName: "系统管理员", action: "DOCUMENT_REPLACED", targetType: "DOCUMENT", targetId: "11", detail: "知识库“门店运营知识库”上传 临期与报损管理-新版.md 替换 临期与报损管理.md" },
  { hoursAgo: 26, actorId: 3, actorName: "张店长", action: "SETTING_CHANGED", targetType: "SETTING", targetId: "SUGGESTED_QUESTIONS", detail: "修改系统设置“推荐问题”" },
  { hoursAgo: 50, actorId: 1, actorName: "系统管理员", action: "ROLE_UPDATED", targetType: "ROLE", targetId: "4", detail: "修改角色“店长”的权限点" },
  { hoursAgo: 75, actorId: 1, actorName: "系统管理员", action: "ACCOUNT_PASSWORD_RESET", targetType: "ACCOUNT", targetId: "6", detail: "重置账号 reset01 的密码" },
  { hoursAgo: 120, actorId: 9, actorName: null, action: "DOCUMENT_DELETED", targetType: "DOCUMENT", targetId: "18", detail: "删除知识库“商品知识库”里的资料 旧版价目表.xlsx" },
  { hoursAgo: 24 * 40, actorId: 1, actorName: "系统管理员", action: "ACCOUNTS_IMPORTED", targetType: "ACCOUNT", targetId: "-", detail: "批量导入 12 个账号" },
];

function records(): AuditRecord[] {
  const now = Date.now();
  return SAMPLES.map(({ hoursAgo, ...record }, index) => ({
    ...record,
    id: SAMPLES.length - index,
    occurredAt: new Date(now - hoursAgo * 3_600_000).toISOString(),
  }));
}

export const auditHandlers = [
  http.get("/api/audit-logs", ({ request }) => {
    const denied = forbidden("audit:view");
    if (denied) return denied;
    const url = new URL(request.url);
    const days = Number(url.searchParams.get("days") ?? 30);
    const action = url.searchParams.get("action");
    const since = Date.now() - days * 86_400_000;
    const list = records()
      .filter((record) => new Date(record.occurredAt).getTime() >= since)
      .filter((record) => !action || record.action === action);
    return HttpResponse.json(paginate(list, url));
  }),
];
