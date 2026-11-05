import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { TableCell, TableRow } from "@/components/ui/table";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { cn, formatDateTime } from "@/lib/utils";
import { type Account } from "./api";

export function isLocked(account: Account) {
  return account.lockedUntil !== null && new Date(account.lockedUntil) > new Date();
}

export function AccountRow({
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
      <TableCell className={cn("font-medium", !account.enabled && "text-muted-foreground line-through")}>
        {account.displayName}
      </TableCell>
      <TableCell className="text-muted-foreground">{account.username}</TableCell>
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
      <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">
        {locked && account.lockedUntil ? formatDateTime(account.lockedUntil) : "—"}
      </TableCell>
      <TableCell className="text-right">
        {account.superAdmin ? (
          <span className="text-xs text-muted-foreground">内置，不能修改</span>
        ) : (
          <div className="flex justify-end gap-1">
            <Can permission={PERMISSIONS.USER_ASSIGN_ROLE}>
              <Button variant="ghost" size="sm" aria-label={`改角色 ${account.displayName}`} onClick={onRoles}>
                改角色
              </Button>
            </Can>
            <Can permission={PERMISSIONS.USER_RESET_PASSWORD}>
              <Button variant="ghost" size="sm" aria-label={`重置密码 ${account.displayName}`} onClick={onPassword}>
                重置密码
              </Button>
            </Can>
            {locked && (
              <Can permission={PERMISSIONS.USER_UPDATE}>
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
            <Can permission={PERMISSIONS.USER_DISABLE}>
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
