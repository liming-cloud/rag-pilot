import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { fetchRoles, roleKeys } from "@/features/roles/api";
import { fetchUsers, userKeys } from "@/features/users/api";
import { ErrorMessage } from "@/lib/errors";
import { dataKeys, fetchDataGrant, type GranteeType } from "./api";
import { GrantEditor } from "./GrantEditor";

type Target = { type: GranteeType; id: number; name: string };

/**
 * 工具与数据范围授权（D-056）：先选一个角色或账号，再勾选它能调用的工具和能查看的站点 / 区域。判定只在后端一处，
 * 管理员也要授权才能查到数据；账号的可用范围 = 本人授权 ∪ 所属角色授权，所以常见做法是按角色授权。
 */
export function DataGrantsPage() {
  const [kind, setKind] = useState<GranteeType>("ROLE");
  const [target, setTarget] = useState<Target | null>(null);

  return (
    <main className="flex flex-col gap-4 p-4">
      <header>
        <h1 className="text-xl font-semibold">数据范围授权</h1>
        <p className="text-sm text-muted-foreground">
          授权给角色或账号：可调用的工具和可查看的站点 / 区域。账号的可用范围 = 本人授权 + 所属角色授权，变更立即生效；
          没有授权的账号（包括管理员）不能查询业务数据。
        </p>
      </header>
      <div role="radiogroup" aria-label="授权对象" className="flex gap-2">
        {(["ROLE", "USER"] as const).map((option) => (
          <Button
            key={option}
            type="button"
            size="sm"
            variant={kind === option ? "default" : "outline"}
            role="radio"
            aria-checked={kind === option}
            onClick={() => {
              setKind(option);
              setTarget(null);
            }}
          >
            {option === "ROLE" ? "角色" : "账号"}
          </Button>
        ))}
      </div>
      {kind === "ROLE" ? <RolePicker onPick={setTarget} /> : <UserPicker onPick={setTarget} />}
      {target ? <TargetEditor key={`${target.type}-${target.id}`} target={target} /> : (
        <p className="rounded-xl border border-dashed p-6 text-center text-sm text-muted-foreground">
          先选择要授权的{kind === "ROLE" ? "角色" : "账号"}。
        </p>
      )}
    </main>
  );
}

function TargetEditor({ target }: { target: Target }) {
  const grant = useQuery({
    queryKey: dataKeys.grant(target.type, target.id),
    queryFn: () => fetchDataGrant(target.type, target.id),
  });
  return (
    <>
      <ErrorMessage error={grant.error} />
      {grant.data && <GrantEditor type={target.type} id={target.id} name={target.name} initial={grant.data} />}
    </>
  );
}

function RolePicker({ onPick }: { onPick: (target: Target | null) => void }) {
  const roles = useQuery({ queryKey: roleKeys.all, queryFn: fetchRoles });
  const [selected, setSelected] = useState("");
  return (
    <div>
      <ErrorMessage error={roles.error} />
      <Label className="flex max-w-sm flex-col gap-1 text-sm">
        角色
        <NativeSelect
          value={selected}
          onChange={(event) => {
            setSelected(event.target.value);
            const role = roles.data?.find((candidate) => String(candidate.id) === event.target.value);
            onPick(role ? { type: "ROLE", id: role.id, name: role.name } : null);
          }}
        >
          <NativeSelectOption value="">选择角色</NativeSelectOption>
          {roles.data
            ?.filter((role) => role.builtIn !== "SUPER_ADMIN")
            .map((role) => (
              <NativeSelectOption key={role.id} value={role.id}>
                {role.name}（{role.userCount} 个账号）
              </NativeSelectOption>
            ))}
        </NativeSelect>
      </Label>
    </div>
  );
}

function UserPicker({ onPick }: { onPick: (target: Target | null) => void }) {
  const [keyword, setKeyword] = useState("");
  const [search, setSearch] = useState("");
  const users = useQuery({
    queryKey: userKeys.list({ keyword: search, size: 10 }),
    queryFn: () => fetchUsers({ keyword: search, size: 10 }),
  });
  return (
    <div className="flex max-w-xl flex-col gap-2">
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
          <Input value={keyword} placeholder="用户名或显示名" onChange={(event) => setKeyword(event.target.value)} />
        </Label>
        <Button type="submit" size="sm" variant="outline" aria-label="搜索">
          <Search aria-hidden="true" className="size-4" />
        </Button>
      </form>
      <ErrorMessage error={users.error} />
      <ul aria-label="搜索结果" className="flex max-h-64 flex-col divide-y overflow-y-auto rounded-md border">
        {users.data?.items.length === 0 && <li className="p-2 text-sm text-muted-foreground">没有找到账号</li>}
        {users.data?.items.map((user) => (
          <li key={user.id} className="flex items-center justify-between gap-2 p-2 text-sm">
            <span>
              {user.displayName}
              <span className="ml-2 text-xs text-muted-foreground">{user.username}</span>
            </span>
            <Button
              size="sm"
              variant="outline"
              aria-label={`选择 ${user.displayName}`}
              onClick={() => onPick({ type: "USER", id: user.id, name: user.displayName })}
            >
              选择
            </Button>
          </li>
        ))}
      </ul>
      {users.data && users.data.total > users.data.items.length && (
        <p className="text-xs text-muted-foreground">
          共 {users.data.total} 个，只显示前 {users.data.items.length} 个，请用关键词缩小范围。
        </p>
      )}
    </div>
  );
}
