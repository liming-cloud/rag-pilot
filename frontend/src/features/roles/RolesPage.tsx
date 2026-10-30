import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Lock, Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Can, useCan } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import {
  createRole,
  deleteRole,
  fetchPermissionCatalog,
  fetchRoles,
  type PermissionGroup,
  type Role,
  updateRole,
} from "./api";

/**
 * 角色与权限（D-038、D-039）：左边选角色。右边默认只读，每个菜单一行，只列出这个角色有的权限；
 * 点“编辑权限”后，每行是“可进入”和一排按钮权限标签，选中的实心、没选的空心，不用在满屏勾选框里对行。
 * 超级管理员只读；还有账号的角色和内置角色不能删。
 */
export function RolesPage() {
  const roles = useQuery({ queryKey: ["roles"], queryFn: fetchRoles });
  const catalog = useQuery({ queryKey: ["permission-catalog"], queryFn: fetchPermissionCatalog });
  const [selectedId, setSelectedId] = useState<number | "new" | null>(null);
  const selected =
    selectedId === "new" ? "new" : (roles.data?.find((role) => role.id === selectedId) ?? roles.data?.[0] ?? null);

  return (
    <main className="mx-auto grid max-w-6xl gap-6 p-6 md:grid-cols-[220px_1fr] md:p-8">
      <aside className="flex flex-col gap-3">
        <h1 className="font-serif text-2xl font-semibold">角色与权限</h1>
        {roles.isError && <p className="text-destructive">读取角色失败，请稍后重试。</p>}
        <nav aria-label="角色" className="flex flex-col gap-1">
          {roles.data?.map((role) => (
            <button
              key={role.id}
              type="button"
              aria-current={selected !== "new" && role.id === selected?.id}
              onClick={() => setSelectedId(role.id)}
              className={cn(
                "flex items-center justify-between rounded-md px-3 py-2 text-left hover:bg-secondary",
                selected !== "new" && role.id === selected?.id && "bg-accent text-accent-foreground",
              )}
            >
              <span>{role.name}</span>
              <span className="text-xs text-muted-foreground">{role.userCount} 人</span>
            </button>
          ))}
        </nav>
        <Can permission="role:create">
          <Button variant="outline" size="sm" onClick={() => setSelectedId("new")}>
            <Plus aria-hidden="true" className="size-4" />
            新建角色
          </Button>
        </Can>
      </aside>
      {catalog.data && selected && (
        <RoleDetail
          key={selected === "new" ? "new" : selected.id}
          role={selected === "new" ? null : selected}
          catalog={catalog.data}
          onSaved={(saved) => setSelectedId(saved.id)}
          onCancelNew={() => setSelectedId(null)}
          onDeleted={() => setSelectedId(null)}
        />
      )}
    </main>
  );
}

function RoleDetail({
  role,
  catalog,
  onSaved,
  onCancelNew,
  onDeleted,
}: {
  role: Role | null;
  catalog: PermissionGroup[];
  onSaved: (role: Role) => void;
  onCancelNew: () => void;
  onDeleted: () => void;
}) {
  const queryClient = useQueryClient();
  const canUpdate = useCan("role:update");
  const isSuper = role?.builtIn === "SUPER_ADMIN";
  const [editing, setEditing] = useState(role === null);
  const [name, setName] = useState(role?.name ?? "");
  const [granted, setGranted] = useState<Set<string>>(new Set(role?.permissions ?? ["chat:use"]));
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["roles"] });

  const save = useMutation({
    mutationFn: () =>
      role
        ? updateRole(role.id, { ...(role.builtIn ? {} : { name: name.trim() }), permissions: [...granted] })
        : createRole({ name: name.trim(), permissions: [...granted] }),
    onSuccess: async (saved) => {
      await refresh();
      setEditing(false);
      onSaved(saved);
    },
  });
  const remove = useMutation({
    mutationFn: () => deleteRole(role!.id),
    onSuccess: async () => {
      await refresh();
      onDeleted();
    },
  });

  function cancel() {
    if (!role) {
      onCancelNew();
      return;
    }
    setName(role.name);
    setGranted(new Set(role.permissions));
    setEditing(false);
  }

  function toggle(group: PermissionGroup, code: string) {
    const next = new Set(granted);
    if (next.has(code)) {
      next.delete(code);
      // 取消“可进入”时，菜单下的按钮权限一起取消。
      if (code === group.view.code) group.buttons.forEach((button) => next.delete(button.code));
    } else {
      next.add(code);
      next.add(group.view.code);
    }
    setGranted(next);
  }

  const has = (code: string) => isSuper || granted.has(code);
  const deletable = role !== null && role.builtIn === null && role.userCount === 0;

  return (
    <section aria-label="角色详情" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        {editing && role?.builtIn == null ? (
          <Label className="flex items-center gap-2">
            角色名称
            <Input value={name} maxLength={20} required className="w-56" onChange={(e) => setName(e.target.value)} />
          </Label>
        ) : (
          <h2 className="text-xl font-medium">{role?.name}</h2>
        )}
        {role?.builtIn && (
          <Badge variant="outline">
            <Lock aria-hidden="true" className="size-3" />
            {isSuper ? "内置 · 只读" : "内置"}
          </Badge>
        )}
        {role && <span className="text-sm text-muted-foreground">{role.userCount} 个账号</span>}
        {!editing && !isSuper && canUpdate && (
          <Button size="sm" variant="outline" className="ml-auto" onClick={() => setEditing(true)}>
            <Pencil aria-hidden="true" className="size-4" />
            编辑权限
          </Button>
        )}
      </div>
      {isSuper && (
        <p className="text-sm text-muted-foreground">超级管理员拥有全部权限，不能修改，全系统只有一个账号。</p>
      )}
      <div className="rounded-lg border bg-card">
        <Table aria-label="权限">
          <TableHeader>
            <TableRow>
              <TableHead className="w-36">菜单</TableHead>
              <TableHead className="w-28">可进入</TableHead>
              <TableHead>可以做的操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {catalog.map((group) => (
              <TableRow key={group.menu} className={cn(!has(group.view.code) && !editing && "text-muted-foreground")}>
                <TableCell className="py-3 font-medium">{group.menu}</TableCell>
                <TableCell>
                  {editing ? (
                    <Label className="flex items-center gap-2 font-normal">
                      <Checkbox
                        aria-label={`可进入 ${group.menu}`}
                        checked={granted.has(group.view.code)}
                        onCheckedChange={() => toggle(group, group.view.code)}
                      />
                      {granted.has(group.view.code) ? "可以" : "不可以"}
                    </Label>
                  ) : has(group.view.code) ? (
                    <span className="flex items-center gap-1 text-success">
                      <Check aria-hidden="true" className="size-4" />
                      可以
                    </span>
                  ) : (
                    "不可以"
                  )}
                </TableCell>
                <TableCell>
                  <div className="flex flex-wrap gap-1.5">
                    {group.buttons.length === 0 && <span className="text-xs text-muted-foreground">—</span>}
                    {editing
                      ? group.buttons.map((button) => (
                          <button
                            key={button.code}
                            type="button"
                            aria-pressed={granted.has(button.code)}
                            onClick={() => toggle(group, button.code)}
                            className={cn(
                              "rounded-full border px-2.5 py-0.5 text-xs",
                              granted.has(button.code)
                                ? "border-transparent bg-primary text-primary-foreground"
                                : "border-input text-muted-foreground hover:bg-secondary",
                            )}
                          >
                            {button.label}
                          </button>
                        ))
                      : (() => {
                          const owned = group.buttons.filter((button) => has(button.code));
                          return owned.length === 0 && group.buttons.length > 0 ? (
                            <span className="text-xs">无</span>
                          ) : (
                            owned.map((button) => <Badge key={button.code}>{button.label}</Badge>)
                          );
                        })()}
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {(save.isError || remove.isError) && (
        <p role="alert" className="text-sm text-destructive">
          {(save.error ?? remove.error)?.message}
        </p>
      )}
      {editing ? (
        <div className="sticky bottom-0 flex items-center gap-2 border-t bg-background py-3">
          <Button size="sm" disabled={save.isPending || !name.trim()} onClick={() => save.mutate()}>
            {role ? "保存" : "新建角色"}
          </Button>
          <Button size="sm" variant="ghost" onClick={cancel}>
            取消
          </Button>
        </div>
      ) : (
        role?.builtIn === null && (
          <Can permission="role:delete">
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                variant="ghost"
                disabled={!deletable || remove.isPending}
                onClick={() => remove.mutate()}
              >
                删除角色
              </Button>
              {!deletable && (
                <span className="text-xs text-muted-foreground">
                  还有 {role.userCount} 个账号属于这个角色，先移走才能删除
                </span>
              )}
            </div>
          </Can>
        )
      )}
    </section>
  );
}
