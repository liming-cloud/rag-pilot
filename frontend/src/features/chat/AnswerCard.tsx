import { SearchX, X } from "lucide-react";
import { lazy, Suspense, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/lib/utils";
import { DataSourceDetail, DataSourceItem } from "./DataSource";
import { FeedbackBar } from "./FeedbackBar";
import { type Answer, type Citation, type DataCitation, reportCitationOpened } from "./api";

/** Markdown 渲染（react-markdown + remark-gfm）单独分包，按需加载；加载完之前先按原文显示，保留换行。 */
const AnswerMarkdown = lazy(() => import("./AnswerMarkdown").then((module) => ({ default: module.AnswerMarkdown })));

/** 答不上来时给员工的下一步（#145 A8）：换个问法的建议，以及还找不到时该问谁，来自行业包。 */
export type RefusalHelp = { tips: string[]; contact: string };

/** 资料的更新日期和状态（#145 A7）：过期、已有新版本的标出来，员工自己判断还能不能照做。 */
function DocumentFreshness({ citation }: { citation: Citation }) {
  return (
    <span className="inline-flex items-center gap-1">
      {citation.documentUpdatedAt && <span>更新于 {formatDateTime(citation.documentUpdatedAt)}</span>}
      {citation.documentState === "EXPIRED" && <Badge variant="destructive">已过失效日期</Badge>}
      {citation.documentState === "REPLACED" && <Badge variant="outline">已有新版本</Badge>}
    </span>
  );
}

/** 问题要查数据而没查到时的标题（#182）；其余拒答是“没有找到答案”。 */
const REFUSAL_TITLES: Partial<Record<NonNullable<Answer["refusalReason"]>, string>> = {
  DATA_DENIED: "无权查看这些数据",
  DATA_UNAVAILABLE: "暂时查不到数据",
};

/** 来源列表的一项：资料或数据出处，按引用编号排在一起。 */
type Source = { kind: "document"; citation: Citation } | { kind: "data"; citation: DataCitation };

/**
 * 一条回答：正文按 Markdown 显示，[n] 可点开原文或数据出处，下方列出来源（资料带更新日期，数据带截至时间）和点赞点踩。
 * 拒答时显示“没有找到答案”（要查的数据无权查看或暂时查不到时如实说明）和下一步，也可以反馈没帮助。
 */
export function AnswerCard({ answer, refusalHelp }: { answer: Answer; refusalHelp?: RefusalHelp }) {
  const [opened, setOpenedCitation] = useState<Citation | null>(null);
  const [openedData, setOpenedData] = useState<DataCitation | null>(null);

  function setOpened(citation: Citation | null) {
    setOpenedCitation(citation);
    if (citation) setOpenedData(null);
    if (citation && answer.id !== null) void reportCitationOpened(answer.id, citation.index);
  }

  function openData(citation: DataCitation) {
    setOpenedData(citation);
    setOpenedCitation(null);
  }

  const sources: Source[] = [
    ...answer.citations.map((citation) => ({ kind: "document" as const, citation })),
    ...answer.dataCitations.map((citation) => ({ kind: "data" as const, citation })),
  ].sort((a, b) => a.citation.index - b.citation.index);
  const dataRefusal = answer.refusalReason !== null && REFUSAL_TITLES[answer.refusalReason] !== undefined;

  if (answer.refused) {
    return (
      <article aria-label="回答" className="flex flex-col gap-2 rounded-2xl border bg-card p-5 shadow-sm">
        <div className="flex gap-3">
          <SearchX aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
          <div className="flex flex-col gap-1">
            <h2 className="font-medium">
              {(answer.refusalReason && REFUSAL_TITLES[answer.refusalReason]) ?? "没有找到答案"}
            </h2>
            <p className="text-sm text-muted-foreground">{answer.text}</p>
            {!dataRefusal && refusalHelp && refusalHelp.tips.length > 0 && (
              <div className="flex flex-col gap-1 text-sm">
                <p>可以这样再试试：</p>
                <ul aria-label="换个问法" className="list-disc pl-5 text-muted-foreground">
                  {refusalHelp.tips.map((tip) => (
                    <li key={tip}>{tip}</li>
                  ))}
                </ul>
              </div>
            )}
            {refusalHelp?.contact && <p className="text-sm">{refusalHelp.contact}</p>}
          </div>
        </div>
        <FeedbackBar answerId={answer.id} allowUp={false} />
      </article>
    );
  }

  return (
    <article aria-label="回答" className="ai-border flex flex-col gap-3 rounded-2xl p-5 shadow-md">
      <Suspense fallback={<p className="leading-relaxed whitespace-pre-line">{answer.text}</p>}>
        <AnswerMarkdown
          text={answer.text}
          citations={answer.citations}
          dataCitations={answer.dataCitations}
          onCite={setOpened}
          onCiteData={openData}
        />
      </Suspense>
      {opened && (
        <section aria-label="引用原文" className="flex flex-col gap-1 rounded-xl border-l-4 border-l-primary bg-accent/60 p-3 text-sm">
          <div className="flex items-start justify-between gap-2">
            <div>
              <p className="font-medium">{opened.documentName}</p>
              <p className="text-muted-foreground">{opened.headingPath}</p>
              <p className="text-xs text-muted-foreground">
                <DocumentFreshness citation={opened} />
              </p>
            </div>
            <Button variant="ghost" size="sm" aria-label="收起原文" onClick={() => setOpened(null)}>
              <X aria-hidden="true" className="size-4" />
            </Button>
          </div>
          {opened.excerpt === null ? (
            <p className="text-muted-foreground">已无权查看原文：所在的知识库已不再授权给你，或原文已被删除。</p>
          ) : (
            <p className="font-display leading-relaxed">{opened.excerpt}</p>
          )}
        </section>
      )}
      {openedData && <DataSourceDetail citation={openedData} onClose={() => setOpenedData(null)} />}
      {sources.length > 0 && (
        <ol aria-label="来源" className="flex flex-col gap-1.5 border-t pt-3 text-sm">
          {sources.map(({ kind, citation }) =>
            kind === "data" ? (
              <li key={citation.index}>
                <DataSourceItem citation={citation} onOpen={() => openData(citation)} />
              </li>
            ) : (
            <li key={citation.index}>
              <button
                type="button"
                onClick={() => setOpened(citation)}
                className="rounded-md text-left text-muted-foreground transition-colors hover:text-primary"
              >
                [{citation.index}] {citation.documentName} · {citation.headingPath}
              </button>{" "}
              <span className="text-xs text-muted-foreground">
                <DocumentFreshness citation={citation} />
              </span>
            </li>
            ),
          )}
        </ol>
      )}
      <FeedbackBar answerId={answer.id} />
    </article>
  );
}
