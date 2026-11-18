import { useSearchParams } from "react-router";
import { PageShell } from "@/components/ui/page-shell";
import { PERMISSIONS, useCan } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import { SetsPanel } from "./SetsPanel";
import { TrialPanel } from "./TrialPanel";

/**
 * 知识质量（#224、D-069）：换模型、整理资料之后，用标准题回答“变好了还是变差了”。
 * “题集”列出标准题集和最近一次运行；“试问”在一个知识库里试问，看召回的原文和回答，可以存为标准题。
 * 回答记录、知识缺口里的问题带着 ?question= 跳到“试问”，先验证再存。标签记在地址里。
 */
export function EvaluationPage() {
  const [params, setParams] = useSearchParams();
  const canRun = useCan(PERMISSIONS.EVAL_RUN);
  const tab = canRun && params.get("tab") === "trial" ? "trial" : "sets";
  const tabs = [
    { key: "sets", label: "题集" },
    ...(canRun ? [{ key: "trial", label: "试问" }] : []),
  ] as const;

  return (
    <PageShell>
      <div>
        <h1 className="font-display text-2xl font-semibold tracking-tight">知识质量</h1>
        <p className="text-sm text-muted-foreground">
          换模型、替换或整理资料之后一键重跑标准题，列出退步的题。只看系统自己能确定的信号：有没有拒答、有没有召回和引用期望的文档。
        </p>
      </div>
      <div role="tablist" aria-label="知识质量" className="flex gap-1 border-b">
        {tabs.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={tab === item.key}
            onClick={() => setParams(item.key === "sets" ? {} : { tab: item.key })}
            className={cn(
              "-mb-px border-b-2 px-4 py-2 text-sm",
              tab === item.key ? "border-primary font-medium" : "border-transparent text-muted-foreground",
            )}
          >
            {item.label}
          </button>
        ))}
      </div>
      {tab === "trial" ? <TrialPanel initialQuestion={params.get("question") ?? ""} /> : <SetsPanel />}
    </PageShell>
  );
}
