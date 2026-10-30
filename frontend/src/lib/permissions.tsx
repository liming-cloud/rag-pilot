import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { json } from "./api";
import { authFetch } from "./auth";

export type RoleRef = { id: number; name: string };

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
  return useQuery({ queryKey: ["me"], queryFn: fetchMe, staleTime: 5 * 60 * 1000 });
}

/** 当前账号有没有这个权限点；还没读到时当作没有。 */
export function useCan(permission: string): boolean {
  return useMe().data?.permissions.includes(permission) ?? false;
}

/**
 * 有权限才显示里面的内容（菜单、按钮）。没有权限的直接隐藏，不置灰（D-038）。
 * 只是为了界面清楚，真正的拦截在后端。
 */
export function Can({ permission, children }: { permission: string; children: ReactNode }) {
  return useCan(permission) ? children : null;
}
