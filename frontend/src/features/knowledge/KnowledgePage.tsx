import { useQuery } from "@tanstack/react-query";
import { Upload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { fetchKnowledgeBases } from "./api";

/**
 * 知识库管理页骨架。按已选定的画布方案 B（上传优先）在第 2 周补全：上传区、处理进度、失败提醒、文档卡片。
 */
export function KnowledgePage() {
  const { data, isPending, isError } = useQuery({ queryKey: ["knowledge-bases"], queryFn: fetchKnowledgeBases });

  return (
    <main className="mx-auto flex max-w-5xl flex-col gap-6 p-8">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="font-serif text-2xl font-semibold">知识库</h1>
        <Button>
          <Upload aria-hidden="true" className="size-4" />
          上传文档
        </Button>
      </div>
      {isPending && <p className="text-muted-foreground">正在读取…</p>}
      {isError && <p className="text-destructive">读取知识库失败，请稍后重试。</p>}
      {data && (
        <ul className="grid gap-3 sm:grid-cols-2">
          {data.map((knowledgeBase) => (
            <li key={knowledgeBase.id} className="rounded-md border bg-card p-4 font-medium">
              {knowledgeBase.name}
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
