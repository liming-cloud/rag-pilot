import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { GapDialog } from "./GapDialog";
import { fetchGaps, GAP_RANGES, type GapGroup, gapKeys, type GapRange, type GapReport } from "./api";

const PAGE_SIZE = 20;

/**
 * 知识缺口（#125、D-047）：把一段时间内答不上来和差评的问题按意思归类，按待处理次数排序，告诉总部该补哪些资料。
 * 点一类在弹窗里看原始记录、用代表问题重新回答验证补的资料是否生效、整类标记已处理。
 * 归类由后端现算，一次返回全部类；关键词筛选和分页在前端做。
 */
export function GapsPage() {
  const [days, setDays] = useState<GapRange>(30);
  const [status, setStatus] = useState<"open" | "all">("open");
  const [keyword, setKeyword] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [opened, setOpened] = useState<GapGroup | null>(null);
  const report = useQuery({
    queryKey: gapKeys.report(days, status),
    queryFn: () => fetchGaps({ days, status }),
    placeholderData: keepPreviousData,
  });

  const matched =
    report.data?.groups.filter(
      (group) => !applied || group.records.some((record) => record.question.includes(applied)),
    ) ?? [];
  const shown = matched.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  function search(event: FormEvent) {
    event.preventDefault();
    setApplied(keyword.trim());
    setPage(1);
  }

  return (
    <main className="mx-auto flex max-w-6xl flex-col gap-4 p-6 md:p-8">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="mr-auto font-serif text-2xl font-semibold">知识缺口</h1>
        {GAP_RANGES.map((range) => (
          <Button
            key={range}
            size="sm"
            variant={days === range ? "default" : "outline"}
            aria-pressed={days === range}
            onClick={() => {
              setDays(range);
              setPage(1);
            }}
          >
            近 {range} 天
          </Button>
        ))}
      </div>
      <form role="search" aria-label="筛选知识缺口" onSubmit={search} className="flex flex-wrap items-end gap-2">
        <Label className="flex flex-col gap-1 text-sm">
          处理状态
          <NativeSelect
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as "open" | "all");
              setPage(1);
            }}
          >
            <NativeSelectOption value="open">有待处理的</NativeSelectOption>
            <NativeSelectOption value="all">全部</NativeSelectOption>
          </NativeSelect>
        </Label>
        <Label className="flex flex-col gap-1 text-sm">
          问题关键词
          <Input value={keyword} className="w-56" onChange={(event) => setKeyword(event.target.value)} />
        </Label>
        <Button type="submit" size="sm" variant="outline">
          <Search aria-hidden="true" className="size-4" />
          查询
        </Button>
      </form>
      {report.data && <Summary report={report.data} />}
      <ErrorMessage error={report.error} className="text-base" />
      <div className="rounded-lg border bg-card">
        <Table aria-label="知识缺口">
          <TableHeader>
            <TableRow>
              <TableHead>代表问题</TableHead>
              <TableHead className="w-24 text-right">待处理 / 共</TableHead>
              <TableHead className="w-32">类型</TableHead>
              <TableHead className="hidden w-40 lg:table-cell">知识库</TableHead>
              <TableHead className="w-28">最近一次</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {report.data && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="py-6 text-center text-muted-foreground">
                  {applied || status === "all" ? "没有符合条件的缺口" : "这段时间没有待处理的缺口"}
                </TableCell>
              </TableRow>
            )}
            {shown.map((group) => (
              <GapRow key={group.id} group={group} onOpen={() => setOpened(group)} />
            ))}
          </TableBody>
        </Table>
      </div>
      {report.data && (
        <Pagination page={page} size={PAGE_SIZE} total={matched.length} onChange={(next) => setPage(next.page ?? 1)} />
      )}
      <Dialog open={opened !== null} onOpenChange={(open) => !open && setOpened(null)}>
        {opened && <GapDialog group={opened} onDone={() => setOpened(null)} />}
      </Dialog>
    </main>
  );
}

/** 范围、归类方式、截断提示。退回按原文分组时说明原因，免得管理员以为同义问法没有被合并是正常的。 */
function Summary({ report }: { report: GapReport }) {
  return (
    <div className="flex flex-col gap-1 text-sm text-muted-foreground">
      <p>
        {report.from} 至 {report.to}：{report.records} 条答不上来或差评，归成 {report.groups.length} 类。
        已处理的记录不计入待处理，但仍留在类里。
      </p>
      {report.grouping === "TEXT" && (
        <p role="status" className="text-destructive">
          嵌入模型暂时不可用，这次按问题原文分组，意思相同的不同问法可能分散在几类里。
        </p>
      )}
      {report.truncated && <p>记录超过 1000 条，只归类了最近的 1000 条；缩短时间范围可以看全。</p>}
    </div>
  );
}

function GapRow({ group, onOpen }: { group: GapGroup; onOpen: () => void }) {
  return (
    <TableRow
      tabIndex={0}
      aria-label={`查看缺口：${group.question}`}
      className="cursor-pointer hover:bg-secondary"
      onClick={onOpen}
      onKeyDown={(event) => event.key === "Enter" && onOpen()}
    >
      <TableCell className="max-w-md py-2.5">
        <div className="flex flex-col gap-0.5">
          <span className="line-clamp-3 font-medium wrap-anywhere" title={group.question}>
            {group.question}
          </span>
          {group.phrasings > 1 && (
            <span className="text-xs text-muted-foreground">另有 {group.phrasings - 1} 种问法</span>
          )}
        </div>
      </TableCell>
      <TableCell className="text-right tabular-nums">
        {group.openCount === 0 ? (
          <Badge variant="success">已处理</Badge>
        ) : (
          <>
            <span className="font-medium">{group.openCount}</span>
            <span className="text-muted-foreground"> / {group.count}</span>
          </>
        )}
      </TableCell>
      <TableCell>
        <div className="flex flex-wrap gap-1">
          {group.refusedCount > 0 && <Badge variant="destructive">答不上来 {group.refusedCount}</Badge>}
          {group.dislikedCount > 0 && <Badge variant="outline">没帮助 {group.dislikedCount}</Badge>}
        </div>
      </TableCell>
      <TableCell className="hidden text-muted-foreground lg:table-cell">
        <span title={group.knowledgeBases.join("、")}>{knowledgeBaseLabel(group.knowledgeBases)}</span>
      </TableCell>
      <TableCell className="whitespace-nowrap text-muted-foreground">
        {new Date(group.lastAskedAt).toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" })}
      </TableCell>
    </TableRow>
  );
}

/** 最多列两个库，其余用“等 N 个”；员工没点名知识库时检索的是全部可访问的库，列表可能很长。 */
function knowledgeBaseLabel(names: string[]) {
  if (names.length === 0) return "—";
  return names.length <= 2 ? names.join("、") : `${names.slice(0, 2).join("、")} 等 ${names.length} 个`;
}
