import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { fetchRoles, type Role } from "@/features/roles/api";
import { Can } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import { type Account, createUser, fetchUsers, type NewAccount, resetPassword, unlockUser, updateUser } from "./api";

const EMPTY: NewAccount = { username: "", displayName: "", password: "", roleIds: [] };

/**
 * 用户管理（D-038）：开账号并分配角色、改角色、重置密码、停用离职员工、解锁输错密码被锁的账号。
 * 超级管理员不能被改动。能看哪些知识库要到知识库页授权给本人或他的角色。
 */
export function UsersPage() {
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ["users"], queryFn: fetchUsers });
  const roles = useQuery({ queryKey: ["roles"], queryFn: fetchRoles });
  const assignable = roles.data?.filter((role) => role.builtIn !== "SUPER_ADMIN") ?? [];
  const [form, setForm] = useState<NewAccount>(EMPTY);
  const [expanded, setExpanded] = useState<{ id: number; panel: "roles" | "password" } | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["users"] });

  const create = useMutation({
    mutationFn: createUser,
    onSuccess: async () => {
      setForm(EMPTY);
      await refresh();
      await queryClient.invalidateQueries({ queryKey: ["roles"] });
    },
  });
  const toggle = useMutation({
    mutationFn: (account: Account) => updateUser(account.id, { enabled: !account.enabled }),
    onSuccess: refresh,
  });
  const unlock = useMutation({ mutationFn: (account: Account) => unlockUser(account.id), onSuccess: refresh });

  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate({ ...form, username: form.username.trim(), displayName: form.displayName.trim() });
  }

  const field = (key: "username" | "displayName" | "password") => ({
    value: form[key],
    onChange: (event: { target: { value: string } }) => setForm({ ...form, [key]: event.target.value }),
  });

  return (
    <main className="mx-auto flex max-w-5xl flex-col gap-6 p-6 md:p-8">
      <h1 className="font-serif text-2xl font-semibold">用户</h1>
      <Can permission="user:create">
        <form
          onSubmit={submit}
          aria-label="新建账号"
          className="grid gap-3 rounded-lg border bg-card p-4 sm:grid-cols-3"
        >
          <Label className="flex flex-col gap-1">
            用户名
            <Input autoComplete="off" required {...field("username")} />
          </Label>
          <Label className="flex flex-col gap-1">
            显示名
            <Input required {...field("displayName")} />
          </Label>
          <Label className="flex flex-col gap-1">
            初始密码（8–64 位）
            <Input type="password" autoComplete="new-password" required {...field("password")} />
          </Label>
          <RolePicker
            roles={assignable}
            selected={form.roleIds}
            onChange={(roleIds) => setForm({ ...form, roleIds })}
            className="sm:col-span-3"
          />
          <div className="flex items-center gap-3 sm:col-span-3">
            <Button type="submit" size="sm" disabled={create.isPending || form.roleIds.length === 0}>
              新建账号
            </Button>
            {create.isError && <p className="text-sm text-destructive">{create.error.message}</p>}
          </div>
        </form>
      </Can>
      {users.isError && <p className="text-destructive">{users.error.message}</p>}
      {(toggle.isError || unlock.isError) && (
        <p className="text-sm text-destructive">{(toggle.error ?? unlock.error)?.message}</p>
      )}
      <Table aria-label="账号" className="rounded-lg border bg-card">
        <TableHeader>
          <TableRow>
            <TableHead>账号</TableHead>
            <TableHead>角色</TableHead>
            <TableHead>状态</TableHead>
            <TableHead className="text-right">操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {users.data?.map((account) => (
            <AccountRow
              key={account.id}
              account={account}
              assignable={assignable}
              expanded={expanded?.id === account.id ? expanded.panel : null}
              onExpand={(panel) => setExpanded(panel ? { id: account.id, panel } : null)}
              onToggle={() => toggle.mutate(account)}
              onUnlock={() => unlock.mutate(account)}
              busy={toggle.isPending || unlock.isPending}
            />
          ))}
        </TableBody>
      </Table>
    </main>
  );
}

function AccountRow({
  account,
  assignable,
  expanded,
  onExpand,
  onToggle,
  onUnlock,
  busy,
}: {
  account: Account;
  assignable: Role[];
  expanded: "roles" | "password" | null;
  onExpand: (panel: "roles" | "password" | null) => void;
  onToggle: () => void;
  onUnlock: () => void;
  busy: boolean;
}) {
  const locked = account.lockedUntil !== null && new Date(account.lockedUntil) > new Date();
  return (
    <>
      <TableRow>
        <TableCell>
          <div className="flex flex-col">
            <span className={cn(!account.enabled && "text-muted-foreground line-through")}>{account.displayName}</span>
            <span className="text-xs text-muted-foreground">{account.username}</span>
          </div>
        </TableCell>
        <TableCell>{account.superAdmin ? "超级管理员" : account.roles.map((role) => role.name).join("、")}</TableCell>
        <TableCell>
          <span
            className={cn(
              "text-sm",
              locked ? "text-destructive" : account.enabled ? "text-success" : "text-muted-foreground",
            )}
          >
            {locked ? "已锁定" : account.enabled ? "已启用" : "已停用"}
          </span>
        </TableCell>
        <TableCell className="text-right">
          {account.superAdmin ? (
            <span className="text-xs text-muted-foreground">内置，不能修改</span>
          ) : (
            <div className="flex justify-end gap-1">
              <Can permission="user:assign-role">
                <Button variant="ghost" size="sm" onClick={() => onExpand(expanded === "roles" ? null : "roles")}>
                  改角色
                </Button>
              </Can>
              <Can permission="user:reset-password">
                <Button variant="ghost" size="sm" onClick={() => onExpand(expanded === "password" ? null : "password")}>
                  重置密码
                </Button>
              </Can>
              {locked && (
                <Can permission="user:update">
                  <Button variant="ghost" size="sm" disabled={busy} onClick={onUnlock}>
                    解锁
                  </Button>
                </Can>
              )}
              <Can permission="user:disable">
                <Button
                  variant="outline"
                  size="sm"
                  aria-label={`${account.enabled ? "停用" : "启用"} ${account.displayName}`}
                  disabled={busy}
                  onClick={onToggle}
                >
                  {account.enabled ? "停用" : "启用"}
                </Button>
              </Can>
            </div>
          )}
        </TableCell>
      </TableRow>
      {expanded && (
        <TableRow>
          <TableCell colSpan={4} className="bg-secondary/50">
            {expanded === "roles" ? (
              <RoleEditor account={account} assignable={assignable} onDone={() => onExpand(null)} />
            ) : (
              <PasswordReset account={account} onDone={() => onExpand(null)} />
            )}
          </TableCell>
        </TableRow>
      )}
    </>
  );
}

function RolePicker({
  roles,
  selected,
  onChange,
  className,
}: {
  roles: Role[];
  selected: number[];
  onChange: (ids: number[]) => void;
  className?: string;
}) {
  return (
    <fieldset className={cn("flex flex-wrap items-center gap-x-4 gap-y-2", className)}>
      <legend className="mb-1 text-sm">角色（可以多选）</legend>
      {roles.map((role) => (
        <Label key={role.id} className="flex items-center gap-2 text-sm font-normal">
          <Checkbox
            checked={selected.includes(role.id)}
            onCheckedChange={(checked) =>
              onChange(checked === true ? [...selected, role.id] : selected.filter((id) => id !== role.id))
            }
          />
          {role.name}
        </Label>
      ))}
    </fieldset>
  );
}

function RoleEditor({ account, assignable, onDone }: { account: Account; assignable: Role[]; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [roleIds, setRoleIds] = useState(account.roles.map((role) => role.id));
  const save = useMutation({
    mutationFn: () => updateUser(account.id, { roleIds }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["users"] });
      await queryClient.invalidateQueries({ queryKey: ["roles"] });
      onDone();
    },
  });
  return (
    <div className="flex flex-col gap-3 py-2">
      <RolePicker roles={assignable} selected={roleIds} onChange={setRoleIds} />
      <div className="flex items-center gap-2">
        <Button size="sm" disabled={roleIds.length === 0 || save.isPending} onClick={() => save.mutate()}>
          保存角色
        </Button>
        <Button size="sm" variant="ghost" onClick={onDone}>
          取消
        </Button>
        {save.isError && <p className="text-sm text-destructive">{save.error.message}</p>}
      </div>
    </div>
  );
}

function PasswordReset({ account, onDone }: { account: Account; onDone: () => void }) {
  const [password, setPassword] = useState("");
  const [done, setDone] = useState(false);
  const reset = useMutation({ mutationFn: () => resetPassword(account.id, password), onSuccess: () => setDone(true) });
  if (done) {
    return (
      <div className="flex items-center gap-3 py-2 text-sm">
        已重置。请把临时密码告诉 {account.displayName}，他下次登录时需要先改密码。
        <Button size="sm" variant="ghost" onClick={onDone}>
          关闭
        </Button>
      </div>
    );
  }
  return (
    <form
      className="flex flex-wrap items-end gap-2 py-2"
      onSubmit={(event) => {
        event.preventDefault();
        reset.mutate();
      }}
    >
      <Label className="flex flex-col gap-1 text-sm">
        给 {account.displayName} 的临时密码（8–64 位）
        <Input
          type="password"
          autoComplete="new-password"
          minLength={8}
          maxLength={64}
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </Label>
      <Button type="submit" size="sm" disabled={reset.isPending}>
        重置
      </Button>
      <Button type="button" size="sm" variant="ghost" onClick={onDone}>
        取消
      </Button>
      {reset.isError && <p className="w-full text-sm text-destructive">{reset.error.message}</p>}
    </form>
  );
}
