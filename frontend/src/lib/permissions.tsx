import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { json } from "./api";
import { authFetch } from "./auth";

export type RoleRef = { id: number; name: string };

/**
 * 按钮级权限点（D-038），与后端各上下文 api 包里声明的一一对应；契约测试（src/test/contract.test.ts）对照后端清单检查。
 * 界面判断权限只用这里的常量，不手写字符串：写错一个字母，按钮就会悄悄不显示。
 */
export const PERMISSIONS = {
  CHAT_USE: "chat:use",
  KB_VIEW: "kb:view",
  KB_CREATE: "kb:create",
  KB_UPDATE: "kb:update",
  KB_DELETE: "kb:delete",
  KB_GRANT: "kb:grant",
  DOC_UPLOAD: "doc:upload",
  DOC_REPLACE: "doc:replace",
  DOC_UPDATE: "doc:update",
  DOC_DELETE: "doc:delete",
  ANSWER_VIEW: "answer:view",
  ANSWER_REVIEW: "answer:review",
  ANSWER_RECHECK: "answer:recheck",
  ANSWER_EXPORT: "answer:export",
  STATS_VIEW: "stats:view",
  STATS_EXPORT: "stats:export",
  USER_VIEW: "user:view",
  USER_CREATE: "user:create",
  USER_UPDATE: "user:update",
  USER_ASSIGN_ROLE: "user:assign-role",
  USER_RESET_PASSWORD: "user:reset-password",
  USER_DISABLE: "user:disable",
  USER_IMPORT: "user:import",
  ROLE_VIEW: "role:view",
  ROLE_CREATE: "role:create",
  ROLE_UPDATE: "role:update",
  ROLE_DELETE: "role:delete",
  AUDIT_VIEW: "audit:view",
  MODEL_VIEW: "model:view",
  MODEL_UPDATE: "model:update",
  MODEL_TEST: "model:test",
  SETTING_VIEW: "setting:view",
  SETTING_UPDATE: "setting:update",
} as const;

export type Permission = (typeof PERMISSIONS)[keyof typeof PERMISSIONS];

/** 当前登录的账号，与后端 /api/auth/me 一致。permissions 是按钮级权限点（D-038）。 */
export type Me = {
  id: number;
  username: string;
  displayName: string;
  roles: RoleRef[];
  permissions: string[];
  /** 超级管理员：内置且固定，拥有全部权限。 */
  superAdmin: boolean;
  /** 管理员重置过密码，登录后必须先改密码。 */
  mustChangePassword: boolean;
};

export async function fetchMe(): Promise<Me> {
  return json(await authFetch("/api/auth/me"), "读取当前用户失败");
}

export function useMe() {
  return useQuery({ queryKey: meKey, queryFn: fetchMe, staleTime: 5 * 60 * 1000 });
}

/** 当前账号有没有这个权限点；还没读到时当作没有。 */
export function useCan(permission: Permission): boolean {
  return useMe().data?.permissions.includes(permission) ?? false;
}

/**
 * 有权限才显示里面的内容（菜单、按钮）。没有权限的直接隐藏，不置灰（D-038）。
 * 只是为了界面清楚，真正的拦截在后端。
 */
export function Can({ permission, children }: { permission: Permission; children: ReactNode }) {
  return useCan(permission) ? children : null;
}

/** 当前账号的查询键；登录、改密码后让它失效。 */
export const meKey = ["me"] as const;
