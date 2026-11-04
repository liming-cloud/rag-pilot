import { Info, Lock } from "lucide-react";
import { type ReactNode, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { type IndustryPack } from "./api";

export const VOCABULARY_PAGE_SIZE = 10;

/** 行业包只读：提示词角色、改写提示、行业词表影响检索和回答质量，要改须改行业包并跑评测（D-045）。 */
export function PackTab({ pack, statsZone }: { pack: IndustryPack; statsZone: string }) {
  return (
    <section aria-label="行业包" className="flex flex-col gap-4">
      <p role="note" className="flex items-start gap-2 rounded-md bg-accent px-4 py-3 text-sm text-accent-foreground">
        <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        <span>
          当前行业包：<strong>{pack.name}</strong>（{pack.code}），由部署配置 RAG_INDUSTRY_PACK
          选择，换行业包需要重启。下面的内容直接影响检索和回答质量，这里只能查看；要修改请改行业包并跑评测。
        </span>
      </p>
      <dl className="grid gap-x-4 gap-y-3 rounded-lg border bg-card px-4 py-3 text-sm md:grid-cols-[10rem_1fr]">
        <ReadOnly term="回答的角色">{pack.answerRole}</ReadOnly>
        <ReadOnly term="引用示例">{pack.citationExample}</ReadOnly>
        <ReadOnly term="改写的角色">{pack.rewriteRole}</ReadOnly>
        <ReadOnly term="改写的行业提示">
          <ul className="list-disc pl-5">
            {pack.rewriteHints.map((hint) => (
              <li key={hint}>{hint}</li>
            ))}
          </ul>
        </ReadOnly>
        <ReadOnly term="统计时区">
          {statsZone}
          <span className="ml-2 text-xs text-muted-foreground">部署配置 rag.answering.stats-zone，不属于行业包</span>
        </ReadOnly>
      </dl>
      <Vocabulary entries={pack.vocabulary} />
    </section>
  );
}

export function ReadOnly({ term, children }: { term: string; children: ReactNode }) {
  return (
    <>
      <dt className="flex items-center gap-1 text-muted-foreground">
        <Lock aria-hidden="true" className="size-3" />
        {term}
      </dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </>
  );
}

/** 行业词表可能有几百组：按上位词或包含的词搜索，分页显示。 */
export function Vocabulary({ entries }: { entries: IndustryPack["vocabulary"] }) {
  const [keyword, setKeyword] = useState("");
  const [paging, setPaging] = useState({ page: 1, size: VOCABULARY_PAGE_SIZE });
  const word = keyword.trim();
  const matched = entries.filter(
    (entry) => !word || entry.term.includes(word) || entry.members.some((member) => member.includes(word)),
  );
  const shown = matched.slice((paging.page - 1) * paging.size, paging.page * paging.size);
  // 说明里的例子取自当前行业包的词表，界面代码不写死行业词（D-045）。
  const example = entries.find((entry) => entry.members.length > 0);

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 className="font-medium">行业词表</h2>
          <p className="text-xs text-muted-foreground">
            问题里出现右边的词时，检索前把左边的词加进去
            {example && `，例如问“${example.members[0]}”也能找到写着“${example.term}”的资料`}。共 {entries.length} 组。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Label htmlFor="vocabulary-keyword" className="sr-only">
            搜索词表
          </Label>
          <Input
            id="vocabulary-keyword"
            placeholder="搜索词表"
            className="w-48"
            value={keyword}
            onChange={(event) => {
              setKeyword(event.target.value);
              setPaging((current) => ({ ...current, page: 1 }));
            }}
          />
        </div>
      </div>
      <div className="rounded-lg border bg-card">
        <Table aria-label="行业词表" className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-32">检索时加上</TableHead>
              <TableHead>问题里出现这些词时</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {shown.map((entry) => (
              <TableRow key={entry.term}>
                <TableCell className="font-medium">{entry.term}</TableCell>
                <TableCell>
                  <div className="flex flex-wrap gap-1">
                    {entry.members.map((member) => (
                      <Badge
                        key={member}
                        variant="outline"
                        className={cn(word && member.includes(word) && "border-primary")}
                      >
                        {member}
                      </Badge>
                    ))}
                  </div>
                </TableCell>
              </TableRow>
            ))}
            {shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={2} className="py-6 text-center text-muted-foreground">
                  没有找到包含“{word}”的词
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
      {matched.length > VOCABULARY_PAGE_SIZE && (
        <Pagination page={paging.page} size={paging.size} total={matched.length} onChange={setPaging} />
      )}
    </div>
  );
}
