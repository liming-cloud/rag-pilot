import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FileUp, Plus, Search } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { fetchRoles, roleKeys } from "@/features/roles/api";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { CreateDialog, RolesDialog, PasswordDialog } from "./AccountDialogs";
import { AccountRow } from "./AccountRow";
import { ImportDialog } from "./ImportDialog";
import { type Account, fetchUsers, setUserEnabled, unlockUser, type UserFilter, userKeys } from "./api";

const PAGE_SIZE = 20;

/**
 * 用户管理（D-038、D-039）：上方筛选（关键词、角色、状态），下方分页表格；新建、改角色、重置密码都在弹窗里做。
 * 上线时可以用 CSV 批量导入（#124）。超级管理员不能被改动。能看哪些知识库要到知识库详情的“授权”里授权给本人或他的角色。
 */
export function UsersPage() {
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<UserFilter>({ page: 1, size: PAGE_SIZE });
  const [keyword, setKeyword] = useState("");
  const users = useQuery({
    queryKey: userKeys.list(filter),
    queryFn: () => fetchUsers(filter),
    placeholderData: keepPreviousData,
  });
  const roles = useQuery({ queryKey: roleKeys.all, queryFn: fetchRoles });
  const assignable = roles.data?.filter((role) => role.builtIn !== "SUPER_ADMIN") ?? [];
  const [dialog, setDialog] = useState<
    { kind: "create" | "import" } | { kind: "roles" | "password"; account: Account } | null
  >(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: userKeys.all });
  const toggle = useMutation({
    mutationFn: (account: Account) => setUserEnabled(account.id, !account.enabled),
    onSuccess: refresh,
  });
  const unlock = useMutation({ mutationFn: (account: Account) => unlockUser(account.id), onSuccess: refresh });

  const change = (next: Partial<UserFilter>) => setFilter({ ...filter, ...next, page: next.page ?? 1 });

  function search(event: FormEvent) {
    event.preventDefault();
    change({ keyword: keyword.trim() });
  }

  return (
    <main className="mx-auto flex w-full max-w-[1600px] p-6 md:p-8 xl:px-10 flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-semibold tracking-tight">用户</h1>
        <div className="flex gap-2">
          <Can permission={PERMISSIONS.USER_IMPORT}>
            <Button size="sm" variant="outline" onClick={() => setDialog({ kind: "import" })}>
              <FileUp aria-hidden="true" className="size-4" />
              批量导入
            </Button>
          </Can>
          <Can permission={PERMISSIONS.USER_CREATE}>
            <Button size="sm" onClick={() => setDialog({ kind: "create" })}>
              <Plus aria-hidden="true" className="size-4" />
              新建账号
            </Button>
          </Can>
        </div>
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
      <ErrorMessage error={users.error} className="text-base" />
      {(toggle.isError || unlock.isError) && (
        <ErrorMessage error={toggle.error ?? unlock.error} />
      )}
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <Table aria-label="账号">
          <TableHeader>
            <TableRow>
              <TableHead>显示名</TableHead>
              <TableHead>用户名</TableHead>
              <TableHead>角色</TableHead>
              <TableHead>状态</TableHead>
              <TableHead>锁定到</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {users.data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="py-10 text-center text-muted-foreground">
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
        <Pagination page={users.data.page} size={users.data.size} total={users.data.total} onChange={change} />
      )}
      <Dialog open={dialog !== null} onOpenChange={(open) => !open && setDialog(null)}>
        {dialog?.kind === "create" && <CreateDialog roles={assignable} onDone={() => setDialog(null)} />}
        {dialog?.kind === "import" && <ImportDialog roles={assignable} onDone={() => setDialog(null)} />}
        {dialog?.kind === "roles" && (
          <RolesDialog account={dialog.account} roles={assignable} onDone={() => setDialog(null)} />
        )}
        {dialog?.kind === "password" && <PasswordDialog account={dialog.account} onDone={() => setDialog(null)} />}
      </Dialog>
    </main>
  );
}
