import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { fetchRoles } from "@/features/roles/api";
import { fetchUsers } from "@/features/users/api";
import { fetchMembers, fetchRoleGrants, setMember, setRoleGrant } from "./api";

/**
 * 知识库授权（D-038、D-039）：授权给角色或账号，分两张表。能检索这个库的人 = 授权的账号 + 属于授权角色的账号；
 * 管理员也只能问到授权给自己或自己角色的库。添加授权在弹窗里做，账号按关键词搜索，不一次列出全部账号。
 */
export function GrantsPanel({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const queryClient = useQueryClient();
  const membersKey = ["knowledge-bases", knowledgeBaseId, "members"];
  const roleGrantsKey = ["knowledge-bases", knowledgeBaseId, "role-grants"];
  const roles = useQuery({ queryKey: ["roles"], queryFn: fetchRoles });
  const members = useQuery({ queryKey: membersKey, queryFn: () => fetchMembers(knowledgeBaseId) });
  const roleGrants = useQuery({ queryKey: roleGrantsKey, queryFn: () => fetchRoleGrants(knowledgeBaseId) });
  const [adding, setAdding] = useState(false);

  const revokeMember = useMutation({
    mutationFn: (userId: number) => setMember(knowledgeBaseId, userId, false),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["knowledge-bases"] }),
  });
  const revokeRole = useMutation({
    mutationFn: (roleId: number) => setRoleGrant(knowledgeBaseId, roleId, false),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["knowledge-bases"] }),
  });

  const roleIds = new Set(roleGrants.data?.map((grant) => grant.roleId));
  const grantedRoles = roles.data?.filter((role) => roleIds.has(role.id)) ?? [];
  const error = roles.error ?? members.error ?? roleGrants.error ?? revokeMember.error ?? revokeRole.error;

  return (
    <section aria-label="授权" className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          能检索这个库的人 = 授权的账号 + 属于授权角色的账号，变更立即生效。
        </p>
        <Button size="sm" onClick={() => setAdding(true)}>
          <Plus aria-hidden="true" className="size-4" />
          添加授权
        </Button>
      </div>
      {error && <p className="text-sm text-destructive">{error.message}</p>}
      <div className="rounded-lg border bg-card">
        <Table aria-label="授权的角色">
          <TableHeader>
            <TableRow>
              <TableHead>角色</TableHead>
              <TableHead>账号数</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {grantedRoles.length === 0 && (
              <TableRow>
                <TableCell colSpan={3} className="py-4 text-center text-muted-foreground">
                  还没有授权给角色
                </TableCell>
              </TableRow>
            )}
            {grantedRoles.map((role) => (
              <TableRow key={role.id}>
                <TableCell className="py-2.5">{role.name}</TableCell>
                <TableCell className="text-muted-foreground">{role.userCount}</TableCell>
                <TableCell className="text-right">
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-label={`收回 ${role.name}`}
                    disabled={revokeRole.isPending}
                    onClick={() => revokeRole.mutate(role.id)}
                  >
                    收回
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <div className="rounded-lg border bg-card">
        <Table aria-label="授权的账号">
          <TableHeader>
            <TableRow>
              <TableHead>账号</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {members.data?.length === 0 && (
              <TableRow>
                <TableCell colSpan={2} className="py-4 text-center text-muted-foreground">
                  还没有单独授权的账号
                </TableCell>
              </TableRow>
            )}
            {members.data?.map((member) => (
              <TableRow key={member.userId}>
                <TableCell className="py-2.5">
                  {member.displayName}
                  <span className="ml-2 text-xs text-muted-foreground">{member.username}</span>
                </TableCell>
                <TableCell className="text-right">
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-label={`收回 ${member.displayName}`}
                    disabled={revokeMember.isPending}
                    onClick={() => revokeMember.mutate(member.userId)}
                  >
                    收回
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <Dialog open={adding} onOpenChange={setAdding}>
        {adding && (
          <AddGrantDialog
            knowledgeBaseId={knowledgeBaseId}
            grantedRoleIds={roleIds}
            grantedUserIds={new Set(members.data?.map((member) => member.userId))}
            onDone={() => setAdding(false)}
          />
        )}
      </Dialog>
    </section>
  );
}

function AddGrantDialog({
  knowledgeBaseId,
  grantedRoleIds,
  grantedUserIds,
  onDone,
}: {
  knowledgeBaseId: number;
  grantedRoleIds: Set<number>;
  grantedUserIds: Set<number>;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<"role" | "user">("role");
  const [roleId, setRoleId] = useState("");
  const [keyword, setKeyword] = useState("");
  const [search, setSearch] = useState("");
  const roles = useQuery({ queryKey: ["roles"], queryFn: fetchRoles });
  const users = useQuery({
    queryKey: ["users", { keyword: search, size: 10 }],
    queryFn: () => fetchUsers({ keyword: search, size: 10 }),
    enabled: kind === "user",
  });
  const grant = useMutation({
    mutationFn: (target: { kind: "role" | "user"; id: number }) =>
      target.kind === "role"
        ? setRoleGrant(knowledgeBaseId, target.id, true)
        : setMember(knowledgeBaseId, target.id, true),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["knowledge-bases"] });
      await queryClient.invalidateQueries({ queryKey: ["roles"] });
      onDone();
    },
  });
  const grantableRoles =
    roles.data?.filter((role) => role.builtIn !== "SUPER_ADMIN" && !grantedRoleIds.has(role.id)) ?? [];

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>添加授权</DialogTitle>
        <DialogDescription>授权给角色，角色里的所有账号都能检索这个库；也可以单独授权给某个账号。</DialogDescription>
      </DialogHeader>
      <div role="radiogroup" aria-label="授权给" className="flex gap-2">
        {(["role", "user"] as const).map((option) => (
          <Button
            key={option}
            type="button"
            size="sm"
            variant={kind === option ? "default" : "outline"}
            role="radio"
            aria-checked={kind === option}
            onClick={() => setKind(option)}
          >
            {option === "role" ? "角色" : "账号"}
          </Button>
        ))}
      </div>
      {kind === "role" ? (
        <Label className="flex flex-col gap-1 text-sm">
          角色
          <NativeSelect value={roleId} onChange={(event) => setRoleId(event.target.value)}>
            <NativeSelectOption value="">选择角色</NativeSelectOption>
            {grantableRoles.map((role) => (
              <NativeSelectOption key={role.id} value={role.id}>
                {role.name}（{role.userCount} 个账号）
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Label>
      ) : (
        <div className="flex flex-col gap-2">
          <form
            role="search"
            className="flex items-end gap-2"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              setSearch(keyword.trim());
            }}
          >
            <Label className="flex flex-1 flex-col gap-1 text-sm">
              搜索账号
              <Input
                value={keyword}
                placeholder="用户名或显示名"
                onChange={(event) => setKeyword(event.target.value)}
              />
            </Label>
            <Button type="submit" size="sm" variant="outline" aria-label="搜索">
              <Search aria-hidden="true" className="size-4" />
            </Button>
          </form>
          <ul aria-label="搜索结果" className="flex max-h-64 flex-col divide-y overflow-y-auto rounded-md border">
            {users.data?.items.length === 0 && <li className="p-2 text-sm text-muted-foreground">没有找到账号</li>}
            {users.data?.items.map((user) => (
              <li key={user.id} className="flex items-center justify-between gap-2 p-2 text-sm">
                <span>
                  {user.displayName}
                  <span className="ml-2 text-xs text-muted-foreground">{user.username}</span>
                </span>
                {grantedUserIds.has(user.id) ? (
                  <span className="text-xs text-muted-foreground">已授权</span>
                ) : (
                  <Button
                    size="sm"
                    variant="outline"
                    aria-label={`授权给 ${user.displayName}`}
                    disabled={grant.isPending}
                    onClick={() => grant.mutate({ kind: "user", id: user.id })}
                  >
                    授权
                  </Button>
                )}
              </li>
            ))}
          </ul>
          {users.data && users.data.total > users.data.items.length && (
            <p className="text-xs text-muted-foreground">
              共 {users.data.total} 个，只显示前 {users.data.items.length} 个，请用关键词缩小范围。
            </p>
          )}
        </div>
      )}
      {grant.isError && <p className="text-sm text-destructive">{grant.error.message}</p>}
      <DialogFooter>
        <Button type="button" variant="ghost" size="sm" onClick={onDone}>
          取消
        </Button>
        {kind === "role" && (
          <Button
            size="sm"
            disabled={!roleId || grant.isPending}
            onClick={() => grant.mutate({ kind: "role", id: Number(roleId) })}
          >
            授权给角色
          </Button>
        )}
      </DialogFooter>
    </DialogContent>
  );
}
