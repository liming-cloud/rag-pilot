import { json, type Page, query } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 一条管理操作的审计记录（#145 C2），与后端 identity 的 AuditRecord 一致。操作人的账号已删除时 actorName 为 null。 */
export type AuditRecord = {
  id: number;
  actorId: number;
  actorName: string | null;
  action: string;
  targetType: string;
  targetId: string;
  detail: string;
  occurredAt: string;
};

/** 操作类型的显示名；新加的操作类型没列在这里时显示原值。 */
export const AUDIT_ACTIONS: Record<string, string> = {
  KB_MEMBER_GRANTED: "知识库授权给账号",
  KB_MEMBER_REVOKED: "撤销账号的知识库授权",
  KB_ROLE_GRANTED: "知识库授权给角色",
  KB_ROLE_REVOKED: "收回角色的知识库授权",
  ROLE_CREATED: "新建角色",
  ROLE_UPDATED: "修改角色",
  ROLE_DELETED: "删除角色",
  ACCOUNT_CREATED: "新建账号",
  ACCOUNT_ENABLED: "启用账号",
  ACCOUNT_DISABLED: "停用账号",
  ACCOUNT_ROLES_CHANGED: "修改账号的角色",
  ACCOUNT_PASSWORD_RESET: "重置密码",
  ACCOUNT_UNLOCKED: "解锁账号",
  ACCOUNTS_IMPORTED: "批量导入账号",
  DOCUMENT_DELETED: "删除资料",
  DOCUMENT_REPLACED: "替换资料",
  SETTING_CHANGED: "修改系统设置",
  SETTING_RESTORED: "系统设置恢复默认",
};

export const AUDIT_RANGES = [7, 30, 90, 365] as const;
export type AuditRange = (typeof AUDIT_RANGES)[number];

/** 筛选：最近几天，只看某一类操作（为空不限），分页。 */
export type AuditFilter = { days: AuditRange; action?: string; page?: number; size?: number };

/** 审计记录的查询键。 */
export const auditKeys = {
  all: ["audit-logs"],
  page: (filter: AuditFilter) => ["audit-logs", filter] as const,
} as const;

export async function fetchAuditLogs(filter: AuditFilter): Promise<Page<AuditRecord>> {
  return json(await authFetch(`/api/audit-logs${query(filter)}`), "读取审计记录失败");
}
