import { useQueryClient } from "@tanstack/react-query";
import { KeyRound, LayoutDashboard, LogOut } from "lucide-react";
import type { ReactNode } from "react";
import { Link, Navigate, Outlet, useLocation, useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { logout } from "@/features/auth/api";
import { type Me, useMe } from "@/lib/permissions";

/** 管理端的菜单，按菜单权限显示（D-038）。模型管理、系统设置在 M4、M5 加入。 */
export const ADMIN_MENUS = [
  { to: "/admin/knowledge", label: "知识库", permission: "kb:view" },
  { to: "/admin/users", label: "用户", permission: "user:view" },
  { to: "/admin/roles", label: "角色与权限", permission: "role:view" },
  { to: "/admin/answers", label: "回答记录", permission: "answer:view" },
  { to: "/admin/stats", label: "使用统计", permission: "stats:view" },
] as const;

export function adminMenusFor(me: Me | undefined) {
  return ADMIN_MENUS.filter((menu) => me?.permissions.includes(menu.permission));
}

export function useSignOut() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return async () => {
    await logout().catch(() => undefined);
    queryClient.clear();
    navigate("/login", { replace: true });
  };
}

/**
 * 员工端外壳：顶部一行放标题、当前用户、“管理后台”入口（有任一管理菜单权限时）、修改密码和退出，下面是问答页。
 */
export function AppShell() {
  const me = useMe();
  const signOut = useSignOut();
  const adminMenus = adminMenusFor(me.data);

  return (
    <div className="flex h-dvh flex-col">
      <header className="flex h-12 shrink-0 items-center gap-3 border-b bg-card px-3">
        <span className="flex-1 font-medium">门店运营知识库</span>
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
export function RequirePermission({ permission, children }: { permission: string; children: ReactNode }) {
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
