import { useQueryClient } from "@tanstack/react-query";
import {
  ChartColumn,
  Cpu,
  KeyRound,
  LayoutDashboard,
  LibraryBig,
  LogOut,
  MessagesSquare,
  ScrollText,
  SearchX,
  Settings,
  ShieldCheck,
  Sparkles,
  Users,
} from "lucide-react";
import type { ReactNode } from "react";
import { Link, Navigate, Outlet, useLocation, useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { logout } from "@/features/auth/api";
import { useBranding } from "@/lib/branding";
import { type Me, type Permission, PERMISSIONS, useMe } from "@/lib/permissions";

/**
 * 管理端的菜单，按菜单权限显示（D-038）。顺序就是左侧菜单的顺序：运营概览是管理端首页、不分组，其余按分组排列；
 * 打开没有权限的页面或管理端首页时，跳到第一个有权限的菜单。
 */
export const ADMIN_MENUS = [
  { to: "/admin/stats", label: "运营概览", permission: PERMISSIONS.STATS_VIEW, group: null, icon: ChartColumn },
  { to: "/admin/knowledge", label: "知识库", permission: PERMISSIONS.KB_VIEW, group: "知识运营", icon: LibraryBig },
  { to: "/admin/gaps", label: "知识缺口", permission: PERMISSIONS.ANSWER_VIEW, group: "知识运营", icon: SearchX },
  { to: "/admin/answers", label: "回答记录", permission: PERMISSIONS.ANSWER_VIEW, group: "知识运营", icon: MessagesSquare },
  { to: "/admin/users", label: "用户", permission: PERMISSIONS.USER_VIEW, group: "账号与权限", icon: Users },
  { to: "/admin/roles", label: "角色与权限", permission: PERMISSIONS.ROLE_VIEW, group: "账号与权限", icon: ShieldCheck },
  { to: "/admin/audit", label: "审计记录", permission: PERMISSIONS.AUDIT_VIEW, group: "账号与权限", icon: ScrollText },
  { to: "/admin/models", label: "模型管理", permission: PERMISSIONS.MODEL_VIEW, group: "系统配置", icon: Cpu },
  { to: "/admin/settings", label: "系统设置", permission: PERMISSIONS.SETTING_VIEW, group: "系统配置", icon: Settings },
] as const;

export type AdminMenu = (typeof ADMIN_MENUS)[number];

export function adminMenusFor(me: Me | undefined) {
  return ADMIN_MENUS.filter((menu) => me?.permissions.includes(menu.permission));
}

export function useSignOut() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return async () => {
    await logout().catch(() => undefined);
    queryClient.clear();
    void navigate("/login", { replace: true });
  };
}

/**
 * 员工端外壳：顶部一行放产品名称（部署配置，D-042）、当前用户、“管理后台”入口（有任一管理菜单权限时）、修改密码和退出，下面是问答页。
 */
export function AppShell() {
  const me = useMe();
  const branding = useBranding();
  const signOut = useSignOut();
  const adminMenus = adminMenusFor(me.data);

  return (
    <div className="flex h-dvh flex-col">
      <header className="glass sticky top-0 z-10 flex h-14 shrink-0 items-center gap-3 border-b px-4">
        <span className="flex flex-1 items-center gap-2.5 font-display text-lg font-semibold tracking-tight">
          <span aria-hidden="true" className="flex size-8 items-center justify-center rounded-lg bg-gradient-primary text-primary-foreground shadow-md">
            <Sparkles className="size-4" />
          </span>
          {branding.name}
        </span>
        {adminMenus.length > 0 && (
          <Button asChild variant="ghost" size="sm">
            <Link to={adminMenus[0]!.to}>
              <LayoutDashboard aria-hidden="true" className="size-4" />
              管理后台
            </Link>
          </Button>
        )}
        {me.data && <span className="text-sm text-muted-foreground">{me.data.displayName}</span>}
        <Button asChild variant="ghost" size="sm">
          <Link to="/change-password" aria-label="修改密码">
            <KeyRound aria-hidden="true" className="size-4" />
          </Link>
        </Button>
        <Button variant="ghost" size="sm" aria-label="退出" onClick={() => void signOut()}>
          <LogOut aria-hidden="true" className="size-4" />
          退出
        </Button>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto">
        <Outlet />
      </div>
    </div>
  );
}

/** 管理员重置过密码时，改完密码前只能待在修改密码页（D-038）。 */
export function RequirePasswordChanged({ children }: { children: ReactNode }) {
  const me = useMe();
  const location = useLocation();
  if (me.isPending) {
    return null;
  }
  if (me.data?.mustChangePassword && location.pathname !== "/change-password") {
    return <Navigate to="/change-password" replace />;
  }
  return children;
}

/** 没有这个菜单权限时跳到第一个有权限的管理菜单；一个都没有时回问答页。读取当前用户期间先不渲染，避免闪出 403。 */
export function RequirePermission({ permission, children }: { permission: Permission; children: ReactNode }) {
  const me = useMe();
  if (me.isPending) {
    return null;
  }
  if (me.data?.permissions.includes(permission)) {
    return children;
  }
  const first = adminMenusFor(me.data)[0];
  return <Navigate to={first?.to ?? "/chat"} replace />;
}

/** 管理端首页：第一个有权限的菜单，通常是运营概览。 */
export function AdminHome() {
  const me = useMe();
  if (me.isPending) {
    return null;
  }
  return <Navigate to={adminMenusFor(me.data)[0]?.to ?? "/chat"} replace />;
}
