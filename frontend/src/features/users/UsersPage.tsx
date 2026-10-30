import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { fetchRoles, type Role } from "@/features/roles/api";
import { Can } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import {
  type Account,
  createUser,
  fetchUsers,
  type NewAccount,
  resetPassword,
  unlockUser,
  updateUser,
  type UserFilter,
} from "./api";

const PAGE_SIZE = 20;

/**
 * 用户管理（D-038、D-039）：上方筛选（关键词、角色、状态），下方分页表格；新建、改角色、重置密码都在弹窗里做。
 * 超级管理员不能被改动。能看哪些知识库要到知识库详情的“授权”里授权给本人或他的角色。
 */
export function UsersPage() {
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<UserFilter>({ page: 1, size: PAGE_SIZE });
  const [keyword, setKeyword] = useState("");
  const users = useQuery({
    queryKey: ["users", filter],
    queryFn: () => fetchUsers(filter),
    placeholderData: keepPreviousData,
  });
  const roles = useQuery({ queryKey: ["roles"], queryFn: fetchRoles });
  const assignable = roles.data?.filter((role) => role.builtIn !== "SUPER_ADMIN") ?? [];
  const [dialog, setDialog] = useState<{ kind: "create" } | { kind: "roles" | "password"; account: Account } | null>(
    null,
  );
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["users"] });
  const toggle = useMutation({
    mutationFn: (account: Account) => updateUser(account.id, { enabled: !account.enabled }),
    onSuccess: refresh,
  });
  const unlock = useMutation({ mutationFn: (account: Account) => unlockUser(account.id), onSuccess: refresh });

  const change = (next: Partial<UserFilter>) => setFilter({ ...filter, ...next, page: next.page ?? 1 });

  function search(event: FormEvent) {
    event.preventDefault();
    change({ keyword: keyword.trim() });
  }

  return (
    <main className="mx-auto flex max-w-6xl flex-col gap-4 p-6 md:p-8">
      <div className="flex items-center justify-between">
        <h1 className="font-serif text-2xl font-semibold">用户</h1>
        <Can permission="user:create">
          <Button size="sm" onClick={() => setDialog({ kind: "create" })}>
            <Plus aria-hidden="true" className="size-4" />
            新建账号
          </Button>
        </Can>
      </div>
      <form role="search" aria-label="筛选账号" onSubmit={search} className="flex flex-wrap items-end gap-2">
        <Label className="flex flex-col gap-1 text-sm">
          关键词
          <Input
            value={keyword}
            placeholder="用户名或显示名"
            className="w-56"
            onChange={(event) => setKeyword(event.target.value)}
          />
        </Label>
        <Label className="flex flex-col gap-1 text-sm">
          角色
          <NativeSelect
            value={filter.roleId ?? ""}
            onChange={(event) => change({ roleId: event.target.value ? Number(event.target.value) : null })}
          >
            <NativeSelectOption value="">全部角色</NativeSelectOption>
            {roles.data?.map((role) => (
              <NativeSelectOption key={role.id} value={role.id}>
                {role.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Label>
        <Label className="flex flex-col gap-1 text-sm">
          状态
          <NativeSelect
            value={filter.status ?? ""}
            onChange={(event) => change({ status: (event.target.value || null) as UserFilter["status"] })}
          >
            <NativeSelectOption value="">全部状态</NativeSelectOption>
            <NativeSelectOption value="enabled">已启用</NativeSelectOption>
            <NativeSelectOption value="disabled">已停用</NativeSelectOption>
            <NativeSelectOption value="locked">已锁定</NativeSelectOption>
          </NativeSelect>
        </Label>
        <Button type="submit" size="sm" variant="outline">
          <Search aria-hidden="true" className="size-4" />
          查询
        </Button>
      </form>
      {users.isError && <p className="text-destructive">{users.error.message}</p>}
      {(toggle.isError || unlock.isError) && (
        <p className="text-sm text-destructive">{(toggle.error ?? unlock.error)?.message}</p>
      )}
      <div className="rounded-lg border bg-card">
        <Table aria-label="账号">
          <TableHeader>
            <TableRow>
              <TableHead>账号</TableHead>
              <TableHead>角色</TableHead>
              <TableHead>状态</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {users.data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={4} className="py-6 text-center text-muted-foreground">
                  没有符合条件的账号
                </TableCell>
              </TableRow>
            )}
            {users.data?.items.map((account) => (
              <AccountRow
                key={account.id}
                account={account}
                busy={toggle.isPending || unlock.isPending}
                onRoles={() => setDialog({ kind: "roles", account })}
                onPassword={() => setDialog({ kind: "password", account })}
                onToggle={() => toggle.mutate(account)}
                onUnlock={() => unlock.mutate(account)}
              />
            ))}
          </TableBody>
        </Table>
      </div>
      {users.data && (
        <Pagination
          page={users.data.page}
          size={users.data.size}
          total={users.data.total}
          onPageChange={(page) => change({ page })}
        />
      )}
      <Dialog open={dialog !== null} onOpenChange={(open) => !open && setDialog(null)}>
        {dialog?.kind === "create" && <CreateDialog roles={assignable} onDone={() => setDialog(null)} />}
        {dialog?.kind === "roles" && (
          <RolesDialog account={dialog.account} roles={assignable} onDone={() => setDialog(null)} />
        )}
        {dialog?.kind === "password" && <PasswordDialog account={dialog.account} onDone={() => setDialog(null)} />}
      </Dialog>
    </main>
  );
}

function isLocked(account: Account) {
  return account.lockedUntil !== null && new Date(account.lockedUntil) > new Date();
}

function AccountRow({
  account,
  busy,
  onRoles,
  onPassword,
  onToggle,
  onUnlock,
}: {
  account: Account;
  busy: boolean;
  onRoles: () => void;
  onPassword: () => void;
  onToggle: () => void;
  onUnlock: () => void;
}) {
  const locked = isLocked(account);
  return (
    <TableRow>
      <TableCell>
        <div className="flex flex-col">
          <span className={cn(!account.enabled && "text-muted-foreground line-through")}>{account.displayName}</span>
          <span className="text-xs text-muted-foreground">{account.username}</span>
        </div>
      </TableCell>
      <TableCell>
        <div className="flex flex-wrap gap-1">
          {account.superAdmin ? (
            <Badge>超级管理员</Badge>
          ) : (
            account.roles.map((role) => (
              <Badge key={role.id} variant="outline">
                {role.name}
              </Badge>
            ))
          )}
        </div>
      </TableCell>
      <TableCell>
        {locked ? (
          <Badge variant="destructive">已锁定</Badge>
        ) : account.enabled ? (
          <Badge variant="success">已启用</Badge>
        ) : (
          <Badge variant="outline">已停用</Badge>
        )}
      </TableCell>
      <TableCell className="text-right">
        {account.superAdmin ? (
          <span className="text-xs text-muted-foreground">内置，不能修改</span>
        ) : (
          <div className="flex justify-end gap-1">
            <Can permission="user:assign-role">
              <Button variant="ghost" size="sm" aria-label={`改角色 ${account.displayName}`} onClick={onRoles}>
                改角色
              </Button>
            </Can>
            <Can permission="user:reset-password">
              <Button variant="ghost" size="sm" aria-label={`重置密码 ${account.displayName}`} onClick={onPassword}>
                重置密码
              </Button>
            </Can>
            {locked && (
              <Can permission="user:update">
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`解锁 ${account.displayName}`}
                  disabled={busy}
                  onClick={onUnlock}
                >
                  解锁
                </Button>
              </Can>
            )}
            <Can permission="user:disable">
              <Button
                variant="ghost"
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
  );
}

function RolePicker({
  roles,
  selected,
  onChange,
}: {
  roles: Role[];
  selected: number[];
  onChange: (ids: number[]) => void;
}) {
  return (
    <fieldset className="flex flex-wrap items-center gap-x-4 gap-y-2">
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

function useRefreshUsers() {
  const queryClient = useQueryClient();
  return async () => {
    await queryClient.invalidateQueries({ queryKey: ["users"] });
    await queryClient.invalidateQueries({ queryKey: ["roles"] });
  };
}

function CreateDialog({ roles, onDone }: { roles: Role[]; onDone: () => void }) {
  const refresh = useRefreshUsers();
  const [form, setForm] = useState<NewAccount>({ username: "", displayName: "", password: "", roleIds: [] });
  const create = useMutation({
    mutationFn: () => createUser({ ...form, username: form.username.trim(), displayName: form.displayName.trim() }),
    onSuccess: async () => {
      await refresh();
      onDone();
    },
  });
  const field = (key: "username" | "displayName" | "password") => ({
    value: form[key],
    onChange: (event: { target: { value: string } }) => setForm({ ...form, [key]: event.target.value }),
  });

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>新建账号</DialogTitle>
        <DialogDescription>建好后把用户名和初始密码交给员工；能看哪些知识库在知识库的“授权”里设置。</DialogDescription>
      </DialogHeader>
      <form
        aria-label="新建账号"
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
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
        <RolePicker roles={roles} selected={form.roleIds} onChange={(roleIds) => setForm({ ...form, roleIds })} />
        {create.isError && <p className="text-sm text-destructive">{create.error.message}</p>}
        <DialogFooter>
          <Button type="button" variant="ghost" size="sm" onClick={onDone}>
            取消
          </Button>
          <Button type="submit" size="sm" disabled={create.isPending || form.roleIds.length === 0}>
            新建账号
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}

function RolesDialog({ account, roles, onDone }: { account: Account; roles: Role[]; onDone: () => void }) {
  const refresh = useRefreshUsers();
  const [roleIds, setRoleIds] = useState(account.roles.map((role) => role.id));
  const save = useMutation({
    mutationFn: () => updateUser(account.id, { roleIds }),
    onSuccess: async () => {
      await refresh();
      onDone();
    },
  });
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>改角色：{account.displayName}</DialogTitle>
        <DialogDescription>只能分配不超过你自己权限的角色。</DialogDescription>
      </DialogHeader>
      <RolePicker roles={roles} selected={roleIds} onChange={setRoleIds} />
      {save.isError && <p className="text-sm text-destructive">{save.error.message}</p>}
      <DialogFooter>
        <Button variant="ghost" size="sm" onClick={onDone}>
          取消
        </Button>
        <Button size="sm" disabled={roleIds.length === 0 || save.isPending} onClick={() => save.mutate()}>
          保存角色
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}

function PasswordDialog({ account, onDone }: { account: Account; onDone: () => void }) {
  const [password, setPassword] = useState("");
  const reset = useMutation({ mutationFn: () => resetPassword(account.id, password) });
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>重置密码：{account.displayName}</DialogTitle>
        <DialogDescription>员工用这个临时密码登录后，必须先改成自己的密码。</DialogDescription>
      </DialogHeader>
      {reset.isSuccess ? (
        <>
          <p className="text-sm">已重置。请把临时密码告诉 {account.displayName}，他下次登录时需要先改密码。</p>
          <DialogFooter>
            <Button size="sm" onClick={onDone}>
              完成
            </Button>
          </DialogFooter>
        </>
      ) : (
        <form
          className="flex flex-col gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            reset.mutate();
          }}
        >
          <Label className="flex flex-col gap-1">
            临时密码（8–64 位）
            <Input
              type="password"
              autoComplete="new-password"
              minLength={8}
              maxLength={64}
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </Label>
          {reset.isError && <p className="text-sm text-destructive">{reset.error.message}</p>}
          <DialogFooter>
            <Button type="button" variant="ghost" size="sm" onClick={onDone}>
              取消
            </Button>
            <Button type="submit" size="sm" disabled={reset.isPending}>
              重置
            </Button>
          </DialogFooter>
        </form>
      )}
    </DialogContent>
  );
}
