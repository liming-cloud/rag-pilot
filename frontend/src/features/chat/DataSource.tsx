import { X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/lib/utils";
import type { DataCitation } from "./api";

/** 来源列表里的一条数据出处（#182）：来源系统 / 数据集，带数据截至时间；结果不全时标出来。 */
export function DataSourceItem({ citation, onOpen }: { citation: DataCitation; onOpen: () => void }) {
  return (
    <>
      <button
        type="button"
        onClick={onOpen}
        className="rounded-md text-left text-muted-foreground transition-colors hover:text-primary"
      >
        [{citation.index}] 数据 · {citation.system} / {citation.dataset}
      </button>{" "}
      <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
        <span>数据截至 {formatDateTime(citation.asOf)}</span>
        {citation.truncated && <Badge variant="outline">只列出了一部分</Badge>}
      </span>
    </>
  );
}

/** 点开的数据出处：来源、查询条件、数据截至和查询时间（规范第 7 节），员工据此核对数字。 */
export function DataSourceDetail({ citation, onClose }: { citation: DataCitation; onClose: () => void }) {
  return (
    <section aria-label="数据出处" className="flex flex-col gap-1 rounded-xl border-l-4 border-l-primary bg-accent/60 p-3 text-sm">
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="font-medium">
            {citation.system} / {citation.dataset}
          </p>
          <p className="text-muted-foreground">业务系统实时查询的数据</p>
        </div>
        <Button variant="ghost" size="sm" aria-label="收起数据出处" onClick={onClose}>
          <X aria-hidden="true" className="size-4" />
        </Button>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
        <dt className="text-muted-foreground">查询条件</dt>
        <dd className="break-all">{citation.conditions || "无"}</dd>
        <dt className="text-muted-foreground">数据截至</dt>
        <dd>{formatDateTime(citation.asOf)}</dd>
        <dt className="text-muted-foreground">查询时间</dt>
        <dd>{formatDateTime(citation.queriedAt)}</dd>
      </dl>
      {citation.truncated && <p className="text-muted-foreground">结果超过条数上限，回答只依据了其中一部分。</p>}
    </section>
  );
}
