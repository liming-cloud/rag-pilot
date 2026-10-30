import { useQuery } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { Link, useParams, useSearchParams } from "react-router";
import { Button } from "@/components/ui/button";
import { useCan } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import { fetchKnowledgeBase } from "./api";
import { DocumentsPanel } from "./DocumentsPanel";
import { GrantsPanel } from "./GrantsPanel";

/**
 * 知识库详情（D-039）：标题下分“文档”“授权”两个标签，标签记在地址里，刷新后不丢。没有授权权限时不显示“授权”。
 */
export function KnowledgeDetailPage() {
  const id = Number(useParams().knowledgeBaseId);
  const [params, setParams] = useSearchParams();
  const canGrant = useCan("kb:grant");
  const tab = params.get("tab") === "grants" && canGrant ? "grants" : "documents";
  const knowledgeBase = useQuery({ queryKey: ["knowledge-bases", id], queryFn: () => fetchKnowledgeBase(id) });

  const tabs = [{ key: "documents", label: "文档" }, ...(canGrant ? [{ key: "grants", label: "授权" }] : [])] as const;

  return (
    <main className="mx-auto flex max-w-6xl flex-col gap-4 p-6 md:p-8">
      <Button asChild variant="ghost" size="sm" className="self-start">
        <Link to="/admin/knowledge">
          <ArrowLeft aria-hidden="true" className="size-4" />
          全部知识库
        </Link>
      </Button>
      {knowledgeBase.isError && <p className="text-destructive">知识库不存在或你没有权限。</p>}
      {knowledgeBase.data && (
        <>
          <h1 className="font-serif text-2xl font-semibold">{knowledgeBase.data.name}</h1>
          <div role="tablist" aria-label="知识库" className="flex gap-1 border-b">
            {tabs.map((item) => (
              <button
                key={item.key}
                type="button"
                role="tab"
                aria-selected={tab === item.key}
                onClick={() => setParams(item.key === "documents" ? {} : { tab: item.key })}
                className={cn(
                  "-mb-px border-b-2 px-4 py-2 text-sm",
                  tab === item.key ? "border-primary font-medium" : "border-transparent text-muted-foreground",
                )}
              >
                {item.label}
              </button>
            ))}
          </div>
          {tab === "documents" ? <DocumentsPanel knowledgeBaseId={id} /> : <GrantsPanel knowledgeBaseId={id} />}
        </>
      )}
    </main>
  );
}
