import { ArrowLeft, ChevronRight, LogOut, PanelLeftClose, PanelLeftOpen, Sparkles } from "lucide-react";
import { useState, useSyncExternalStore } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router";
import { Button } from "@/components/ui/button";
import { useMe } from "@/lib/permissions";
import { APP_VERSION } from "@/lib/version";
import { cn } from "@/lib/utils";
import { type AdminMenu, adminMenusFor, useSignOut } from "./AppShell";

/**
 * 管理端布局（D-038）：左侧树形菜单，只显示有菜单权限的项，一个都没有的分组整组隐藏；分组可以折叠，折叠状态记在
 * 本机。当前页面所在的分组始终展开。底部显示当前账号、回到问答、退出和运行的版本（D-048）。
 *
 * 菜单可以收起成只显示图标的窄栏（#130）：收起时不显示分组标题，鼠标悬停显示菜单名。用户手动收起或展开后记在本机；
 * 没有手动选过时，窗口窄于 1024px 默认收起。
 */
export function AdminLayout() {
  const me = useMe();
  const signOut = useSignOut();
  const menus = adminMenusFor(me.data);
  const [rail, toggleRail] = useRail();

  return (
    <div className="flex h-dvh print:block print:h-auto">
      <aside
        className={cn("glass flex shrink-0 flex-col border-r print:hidden", rail ? "w-14" : "w-56")}
        data-rail={rail}
      >
        <div
          className={cn(
            "flex h-14 items-center font-display font-semibold tracking-tight",
            rail ? "justify-center" : "justify-between pr-2 pl-4",
          )}
        >
          {!rail && (
            <span className="flex items-center gap-2.5">
              <span
                aria-hidden="true"
                className="flex size-7 items-center justify-center rounded-lg bg-gradient-primary text-primary-foreground shadow-md"
              >
                <Sparkles className="size-3.5" />
              </span>
              管理后台
            </span>
          )}
          <Button
            variant="ghost"
            size="icon"
            aria-label={rail ? "展开菜单" : "收起菜单"}
            title={rail ? "展开菜单" : "收起菜单"}
            onClick={toggleRail}
          >
            {rail ? (
              <PanelLeftOpen aria-hidden="true" className="size-4" />
            ) : (
              <PanelLeftClose aria-hidden="true" className="size-4" />
            )}
          </Button>
        </div>
        <nav aria-label="管理菜单" className="flex flex-1 flex-col gap-0.5 overflow-y-auto px-2">
          {menuTree(menus).map((node) =>
            node.group === null ? (
              <MenuLink key={node.menu.to} menu={node.menu} rail={rail} />
            ) : (
              <MenuGroup key={node.group} name={node.group} menus={node.menus} rail={rail} />
            ),
          )}
        </nav>
        <div className="flex flex-col gap-1 border-t p-2">
          <Button
            asChild
            variant="ghost"
            size="sm"
            className={rail ? "justify-center px-0" : "justify-start"}
            title={rail ? "回到问答" : undefined}
          >
            <Link to="/chat">
              <ArrowLeft aria-hidden="true" className="size-4" />
              <span className={cn(rail && "sr-only")}>回到问答</span>
            </Link>
          </Button>
          {me.data && !rail && (
            <span className="px-3 text-xs text-muted-foreground">
              {me.data.displayName} ·{" "}
              {me.data.superAdmin ? "超级管理员" : me.data.roles.map((role) => role.name).join("、")}
            </span>
          )}
          <Button
            variant="ghost"
            size="sm"
            className={rail ? "justify-center px-0" : "justify-start"}
            title={rail ? "退出" : undefined}
            onClick={() => void signOut()}
          >
            <LogOut aria-hidden="true" className="size-4" />
            <span className={cn(rail && "sr-only")}>退出</span>
          </Button>
          {!rail && <span className="px-3 text-xs text-muted-foreground">版本 {APP_VERSION}</span>}
        </div>
      </aside>
      <div className="min-h-0 min-w-0 flex-1 overflow-y-auto print:overflow-visible">
        <Outlet />
      </div>
    </div>
  );
}

const RAIL_KEY = "admin.menu.rail";
const NARROW_QUERY = "(max-width: 1023.98px)";

/** 窄栏状态：本机记下的手动选择优先，没有时按窗口宽度。 */
function useRail(): [boolean, () => void] {
  const [stored, setStored] = useState(() => localStorage.getItem(RAIL_KEY));
  const narrow = useSyncExternalStore(subscribeNarrow, isNarrow);
  const rail = stored === null ? narrow : stored === "collapsed";

  function toggle() {
    const next = rail ? "expanded" : "collapsed";
    localStorage.setItem(RAIL_KEY, next);
    setStored(next);
  }

  return [rail, toggle];
}

function subscribeNarrow(onChange: () => void) {
  const query = window.matchMedia?.(NARROW_QUERY);
  query?.addEventListener("change", onChange);
  return () => query?.removeEventListener("change", onChange);
}

function isNarrow() {
  return window.matchMedia?.(NARROW_QUERY).matches ?? false;
}

type MenuNode = { group: null; menu: AdminMenu } | { group: string; menus: AdminMenu[] };

/** 按 ADMIN_MENUS 的顺序把菜单排成树：不分组的单独一项，同组的归到一起。 */
function menuTree(menus: AdminMenu[]): MenuNode[] {
  const nodes: MenuNode[] = [];
  for (const menu of menus) {
    const last = nodes.at(-1);
    if (menu.group === null) {
      nodes.push({ group: null, menu });
    } else if (last && last.group !== null && last.group === menu.group) {
      last.menus.push(menu);
    } else {
      nodes.push({ group: menu.group, menus: [menu] });
    }
  }
  return nodes;
}

function MenuLink({ menu, rail, nested = false }: { menu: AdminMenu; rail: boolean; nested?: boolean }) {
  const Icon = menu.icon;
  return (
    <NavLink
      to={menu.to}
      title={rail ? menu.label : undefined}
      className={({ isActive }) =>
        cn(
          "flex items-center gap-2 rounded-lg py-2 text-sm transition-colors hover:bg-accent/60",
          rail ? "justify-center" : "px-3",
          nested && !rail && "pl-8",
          isActive && "bg-accent font-medium text-accent-foreground shadow-[inset_3px_0_0_var(--primary)]",
        )
      }
    >
      <Icon aria-hidden="true" className="size-4 shrink-0" />
      <span className={cn(rail && "sr-only")}>{menu.label}</span>
    </NavLink>
  );
}

const COLLAPSED_KEY = "admin.menu.collapsed";

function readCollapsed(): string[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(COLLAPSED_KEY) ?? "[]");
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

/** 菜单分组。窄栏里不显示标题、不能折叠，组内菜单全部列出，组之间用分隔线隔开。 */
function MenuGroup({ name, menus, rail }: { name: string; menus: AdminMenu[]; rail: boolean }) {
  const { pathname } = useLocation();
  const [collapsed, setCollapsed] = useState(() => readCollapsed().includes(name));
  const current = menus.some((menu) => pathname.startsWith(menu.to));
  const open = rail || current || !collapsed;

  function toggle() {
    const next = !collapsed;
    setCollapsed(next);
    const others = readCollapsed().filter((item) => item !== name);
    localStorage.setItem(COLLAPSED_KEY, JSON.stringify(next ? [...others, name] : others));
  }

  return (
    <div role="group" aria-label={name} className={cn("mt-2 flex flex-col gap-0.5", rail && "border-t pt-2")}>
      {!rail && (
        <Button
          variant="ghost"
          size="sm"
          aria-expanded={open}
          disabled={current}
          onClick={toggle}
          className="justify-start px-3 text-xs font-normal text-muted-foreground disabled:opacity-100"
        >
          <ChevronRight aria-hidden="true" className={cn("size-3.5 transition-transform", open && "rotate-90")} />
          {name}
        </Button>
      )}
      {open && menus.map((menu) => <MenuLink key={menu.to} menu={menu} rail={rail} nested />)}
    </div>
  );
}
