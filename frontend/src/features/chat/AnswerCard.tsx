import { SearchX, X } from "lucide-react";
import { lazy, Suspense, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FeedbackBar } from "./FeedbackBar";
import { type Answer, type Citation, reportCitationOpened } from "./api";

/** Markdown 渲染（react-markdown + remark-gfm）单独分包，按需加载；加载完之前先按原文显示，保留换行。 */
const AnswerMarkdown = lazy(() => import("./AnswerMarkdown").then((module) => ({ default: module.AnswerMarkdown })));

/** 答不上来时给员工的下一步（#145 A8）：换个问法的建议，以及还找不到时该问谁，来自行业包。 */
export type RefusalHelp = { tips: string[]; contact: string };

/** 资料的更新日期和状态（#145 A7）：过期、已有新版本的标出来，员工自己判断还能不能照做。 */
function DocumentFreshness({ citation }: { citation: Citation }) {
  return (
    <span className="inline-flex items-center gap-1">
      {citation.documentUpdatedAt && <span>更新于 {citation.documentUpdatedAt.slice(0, 10)}</span>}
      {citation.documentState === "EXPIRED" && <Badge variant="destructive">已过失效日期</Badge>}
      {citation.documentState === "REPLACED" && <Badge variant="outline">已有新版本</Badge>}
    </span>
  );
}

/**
 * 一条回答：正文按 Markdown 显示，[n] 可点开原文，下方列出来源（带资料的更新日期）和点赞点踩。
 * 拒答时显示“没有找到答案”和下一步，也可以反馈没帮助。
 */
export function AnswerCard({ answer, refusalHelp }: { answer: Answer; refusalHelp?: RefusalHelp }) {
  const [opened, setOpenedCitation] = useState<Citation | null>(null);

  function setOpened(citation: Citation | null) {
    setOpenedCitation(citation);
    if (citation && answer.id !== null) void reportCitationOpened(answer.id, citation.index);
  }

  if (answer.refused) {
    return (
      <article aria-label="回答" className="flex flex-col gap-2 rounded-lg border bg-card p-4">
        <div className="flex gap-3">
          <SearchX aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
          <div className="flex flex-col gap-1">
            <h2 className="font-medium">没有找到答案</h2>
            <p className="text-sm text-muted-foreground">{answer.text}</p>
            {refusalHelp && refusalHelp.tips.length > 0 && (
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
    <article aria-label="回答" className="flex flex-col gap-3 rounded-lg border bg-card p-4">
      <Suspense fallback={<p className="leading-relaxed whitespace-pre-line">{answer.text}</p>}>
        <AnswerMarkdown text={answer.text} citations={answer.citations} onCite={setOpened} />
      </Suspense>
      {opened && (
        <section aria-label="引用原文" className="flex flex-col gap-1 rounded-md bg-secondary p-3 text-sm">
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
            <p className="font-serif leading-relaxed">{opened.excerpt}</p>
          )}
        </section>
      )}
      {answer.citations.length > 0 && (
        <ol aria-label="来源" className="flex flex-col gap-1 border-t pt-3 text-sm">
          {answer.citations.map((citation) => (
            <li key={citation.index}>
              <button
                type="button"
                onClick={() => setOpened(citation)}
                className="text-left text-muted-foreground hover:text-foreground"
              >
                [{citation.index}] {citation.documentName} · {citation.headingPath}
              </button>{" "}
              <span className="text-xs text-muted-foreground">
                <DocumentFreshness citation={citation} />
              </span>
            </li>
          ))}
        </ol>
      )}
      <FeedbackBar answerId={answer.id} />
    </article>
  );
}
