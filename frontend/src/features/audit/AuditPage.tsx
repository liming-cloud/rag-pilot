import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { PageShell } from "@/components/ui/page-shell";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { usePagedFilter } from "@/lib/paging";
import { formatDateTime } from "@/lib/utils";
import { AUDIT_ACTIONS, AUDIT_RANGES, AUDIT_TARGETS, type AuditFilter, auditKeys, type AuditRange, fetchAuditLogs } from "./api";

/**
 * 审计记录（#145 C2，audit:view）：谁在什么时候做了哪项管理操作——授权、角色、账号、删除和替换资料、系统设置。
 * 按时间范围和操作类型筛选，新的在前；记录保留 1 年，只能看，不能改。
 */
export function AuditPage() {
  const [filter, change] = usePagedFilter<AuditFilter>({ days: 30 });
  const logs = useQuery({
    queryKey: auditKeys.page(filter),
    queryFn: () => fetchAuditLogs(filter),
    placeholderData: keepPreviousData,
  });

  return (
    <PageShell>
      <div className="flex flex-col gap-1">
        <h1 className="font-display text-2xl font-semibold tracking-tight">审计记录</h1>
        <p className="text-sm text-muted-foreground">管理操作留下的记录，保留 1 年。模型变更见“模型管理”的变更记录。</p>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <Label className="flex flex-col gap-1">
          时间范围
          <NativeSelect
            value={String(filter.days)}
            onChange={(event) => change({ days: Number(event.target.value) as AuditRange })}
          >
            {AUDIT_RANGES.map((days) => (
              <NativeSelectOption key={days} value={days}>
                {days === 365 ? "近 1 年" : `近 ${days} 天`}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Label>
        <Label className="flex flex-col gap-1">
          操作类型
          <NativeSelect
            value={filter.action ?? ""}
            onChange={(event) => change({ action: event.target.value || undefined })}
          >
            <NativeSelectOption value="">全部</NativeSelectOption>
            {Object.entries(AUDIT_ACTIONS).map(([action, label]) => (
              <NativeSelectOption key={action} value={action}>
                {label}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Label>
      </div>
      <ErrorMessage error={logs.error} className="text-base" />
      {logs.data && (
        <>
          <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
          <Table aria-label="审计记录">
            <TableHeader>
              <TableRow>
                <TableHead>时间</TableHead>
                <TableHead>操作人</TableHead>
                <TableHead>操作</TableHead>
                <TableHead>对象</TableHead>
                <TableHead>内容</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {logs.data.items.map((record) => (
                <TableRow key={record.id}>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    {formatDateTime(record.occurredAt)}
                  </TableCell>
                  <TableCell>{record.actorName ?? `已删除的账号 #${record.actorId}`}</TableCell>
                  <TableCell className="whitespace-nowrap">{AUDIT_ACTIONS[record.action] ?? record.action}</TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    {AUDIT_TARGETS[record.targetType] ?? record.targetType}
                    {/^\d+$/.test(record.targetId) && ` #${record.targetId}`}
                  </TableCell>
                  <TableCell className="whitespace-normal wrap-anywhere">{record.detail}</TableCell>
                </TableRow>
              ))}
              {logs.data.items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} className="py-10 text-center text-muted-foreground">
                    这段时间没有这类操作。
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
          </div>
          <Pagination page={logs.data.page} size={logs.data.size} total={logs.data.total} onChange={change} />
        </>
      )}
    </PageShell>
  );
}
