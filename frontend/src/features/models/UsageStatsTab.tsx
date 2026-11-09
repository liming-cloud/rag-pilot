import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Pagination } from "@/components/ui/pagination";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { PriceDialog } from "./PriceDialog";
import {
  fetchUsageReport,
  type ModelPrice,
  modelKeys,
  PURPOSE_NAMES,
  type UsageGroup,
  type UsageGroupBy,
  type UsagePurpose,
} from "./api";

const PAGE_SIZE = 20;

const GROUPS: { value: UsageGroupBy; label: string; column: string }[] = [
  { value: "DETAIL", label: "明细", column: "日期" },
  { value: "DAY", label: "按日", column: "日期" },
  { value: "MONTH", label: "按月", column: "月份" },
  { value: "PURPOSE", label: "按用途", column: "用途" },
  { value: "MODEL", label: "按模型", column: "模型 · 服务商" },
];

/** 看多久：不传 from 就是最近 30 天（后端默认）；其余按天数往前推。 */
const RANGES = [
  { days: 7, label: "最近 7 天" },
  { days: 30, label: "最近 30 天" },
  { days: 90, label: "最近 90 天" },
  { days: 365, label: "最近一年" },
] as const;

const number = (value: number) => value.toLocaleString("zh-CN");

function money(value: number) {
  if (value > 0 && value < 0.01) return "<¥0.01";
  return `¥${value.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function startDate(days: number) {
  const date = new Date();
  date.setDate(date.getDate() - (days - 1));
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function keyLabel(by: UsageGroupBy, key: string) {
  return by === "PURPOSE" ? (PURPOSE_NAMES[key as UsagePurpose] ?? key) : key;
}

/**
 * 模型用量与费用（#169）：每天、每个用途、每个模型用了多少 token、大约花了多少钱。只有聚合数字，不记问题原文和提问的人，
 * 保留一年。单价表在最上面——费用按它手填的单价估算，没填单价的模型不计费用并标出；下面是分页的用量列表，默认看明细，
 * 也可以按日、月、用途、模型合并。看要 model:view，改单价要 model:update。
 */
export function UsageStatsTab() {
  const [groupBy, setGroupBy] = useState<UsageGroupBy>("DETAIL");
  const [days, setDays] = useState<number>(30);
  const [paging, setPaging] = useState({ page: 1, size: PAGE_SIZE });
  const [pricing, setPricing] = useState<ModelPrice | null>(null);
  const filter = { groupBy, ...paging, ...(days === 30 ? {} : { from: startDate(days) }) };
  const report = useQuery({
    queryKey: modelKeys.usageReport(filter),
    queryFn: () => fetchUsageReport(filter),
    placeholderData: keepPreviousData,
  });
  const detail = groupBy === "DETAIL";
  const groups = report.data?.groups;

  return (
    <section aria-label="用量" className="flex flex-col gap-6">
      <PricesTable prices={report.data?.prices ?? []} onEdit={setPricing} />
      <div className="flex flex-col gap-3">
        <h2 className="font-display text-lg font-semibold">用量</h2>
        <div className="flex flex-wrap items-end gap-3">
          <Label className="flex flex-col gap-1 text-sm">
            统计方式
            <NativeSelect
              value={groupBy}
              onChange={(event) => {
                setGroupBy(event.target.value as UsageGroupBy);
                setPaging({ ...paging, page: 1 });
              }}
            >
              {GROUPS.map((item) => (
                <NativeSelectOption key={item.value} value={item.value}>
                  {item.label}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Label>
          <Label className="flex flex-col gap-1 text-sm">
            时间范围
            <NativeSelect
              value={days}
              onChange={(event) => {
                setDays(Number(event.target.value));
                setPaging({ ...paging, page: 1 });
              }}
            >
              {RANGES.map((item) => (
                <NativeSelectOption key={item.days} value={item.days}>
                  {item.label}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Label>
          {report.data && (
            <p className="pb-2 text-sm text-muted-foreground">
              {report.data.from} 至 {report.data.to}，只保留一年
            </p>
          )}
        </div>
        <ErrorMessage error={report.error} />
        {report.data && groups && groups.total > 0 && (
          <p aria-label="合计" className="text-sm">
            合计：调用 {number(report.data.total.calls)} 次，输入 {number(report.data.total.inputTokens)} token，输出{" "}
            {number(report.data.total.outputTokens)} token，估算费用 {money(report.data.total.cost)}
            {report.data.total.unpriced && (
              <Badge variant="outline" className="ml-2" title="有模型没填单价，这部分没算进费用">
                缺单价
              </Badge>
            )}
          </p>
        )}
        <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
          <Table aria-label="用量统计">
            <TableHeader>
              <TableRow>
                <TableHead>{GROUPS.find((item) => item.value === groupBy)!.column}</TableHead>
                {detail && (
                  <>
                    <TableHead>用途</TableHead>
                    <TableHead>模型</TableHead>
                    <TableHead>服务商</TableHead>
                  </>
                )}
                <TableHead className="text-right">调用次数</TableHead>
                <TableHead className="text-right">输入 token</TableHead>
                <TableHead className="text-right">输出 token</TableHead>
                <TableHead className="text-right">估算费用</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {groups?.items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={detail ? 8 : 5} className="py-10 text-center text-muted-foreground">
                    这段时间还没有模型调用
                  </TableCell>
                </TableRow>
              )}
              {groups?.items.map((group) => (
                <UsageRow
                  key={`${group.key} ${group.purpose} ${group.provider} ${group.model}`}
                  label={keyLabel(groupBy, group.key)}
                  group={group}
                  detail={detail}
                />
              ))}
            </TableBody>
          </Table>
        </div>
        {groups && <Pagination page={groups.page} size={groups.size} total={groups.total} onChange={setPaging} />}
      </div>
      <PriceDialog price={pricing} onDone={() => setPricing(null)} />
    </section>
  );
}

function UsageRow({ label, group, detail }: { label: string; group: UsageGroup; detail: boolean }) {
  return (
    <TableRow>
      <TableCell className="max-w-72 truncate" title={label}>
        {label}
      </TableCell>
      {detail && (
        <>
          <TableCell>{group.purpose ? PURPOSE_NAMES[group.purpose] : ""}</TableCell>
          <TableCell className="max-w-64 truncate font-mono text-sm" title={group.model ?? ""}>
            {group.model}
          </TableCell>
          <TableCell className="max-w-48 truncate text-muted-foreground" title={group.provider ?? ""}>
            {group.provider}
          </TableCell>
        </>
      )}
      <TableCell className="text-right tabular-nums">{number(group.calls)}</TableCell>
      <TableCell className="text-right tabular-nums">{number(group.inputTokens)}</TableCell>
      <TableCell className="text-right tabular-nums">{number(group.outputTokens)}</TableCell>
      <TableCell className="text-right tabular-nums">
        {group.unpriced && (
          <Badge variant="outline" className="mr-2" title="有模型没填单价，这部分没算进费用">
            缺单价
          </Badge>
        )}
        {money(group.cost)}
      </TableCell>
    </TableRow>
  );
}

function PricesTable({ prices, onEdit }: { prices: ModelPrice[]; onEdit: (price: ModelPrice) => void }) {
  return (
    <div className="flex flex-col gap-2">
      <h2 className="font-display text-lg font-semibold">单价</h2>
      <p className="text-sm text-muted-foreground">
        按模型手填服务商价目表上的价格（元 / 百万 token），系统不内置价目表。有过调用的模型会列在这里。
      </p>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <Table aria-label="模型单价">
          <TableHeader>
            <TableRow>
              <TableHead>模型</TableHead>
              <TableHead className="text-right">输入（元 / 百万 token）</TableHead>
              <TableHead className="text-right">输出（元 / 百万 token）</TableHead>
              <TableHead className="w-24 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {prices.length === 0 && (
              <TableRow>
                <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                  还没有调用过模型
                </TableCell>
              </TableRow>
            )}
            {prices.map((price) => (
              <TableRow key={price.model}>
                <TableCell className="max-w-72 truncate font-mono text-sm" title={price.model}>
                  {price.model}
                </TableCell>
                <TableCell className="text-right tabular-nums">{price.inputPerMillion ?? "未填"}</TableCell>
                <TableCell className="text-right tabular-nums">{price.outputPerMillion ?? "未填"}</TableCell>
                <TableCell className="text-right">
                  <Can permission={PERMISSIONS.MODEL_UPDATE}>
                    <Button variant="ghost" size="sm" aria-label={`设置 ${price.model} 的单价`} onClick={() => onEdit(price)}>
                      {price.inputPerMillion === null ? "设置" : "修改"}
                    </Button>
                  </Can>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
