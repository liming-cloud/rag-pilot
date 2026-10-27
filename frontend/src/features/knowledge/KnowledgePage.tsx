import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FileText, Plus, Upload } from "lucide-react";
import { type DragEvent, type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  createKnowledgeBase,
  fetchDocuments,
  fetchKnowledgeBases,
  type KnowledgeBase,
  type KnowledgeDocument,
  uploadDocument,
} from "./api";

/**
 * 知识库管理页，按画布方案 B（上传优先）：左边选库，右边先是上传区，再是文档列表和处理结果。
 */
export function KnowledgePage() {
  const queryClient = useQueryClient();
  const knowledgeBases = useQuery({ queryKey: ["knowledge-bases"], queryFn: fetchKnowledgeBases });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const selected = knowledgeBases.data?.find((kb) => kb.id === selectedId) ?? knowledgeBases.data?.[0];

  const [name, setName] = useState("");
  const create = useMutation({
    mutationFn: createKnowledgeBase,
    onSuccess: async (created) => {
      setName("");
      await queryClient.invalidateQueries({ queryKey: ["knowledge-bases"] });
      setSelectedId(created.id);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (name.trim()) {
      create.mutate(name.trim());
    }
  }

  return (
    <main className="mx-auto grid max-w-5xl gap-6 p-6 md:grid-cols-[240px_1fr] md:p-8">
      <aside className="flex flex-col gap-3">
        <h1 className="font-serif text-2xl font-semibold">知识库</h1>
        {knowledgeBases.isPending && <p className="text-muted-foreground">正在读取…</p>}
        {knowledgeBases.isError && <p className="text-destructive">读取知识库失败，请稍后重试。</p>}
        <nav className="flex flex-col gap-1">
          {knowledgeBases.data?.map((kb) => (
            <button
              key={kb.id}
              type="button"
              aria-current={kb.id === selected?.id}
              onClick={() => setSelectedId(kb.id)}
              className={cn(
                "rounded-md px-3 py-2 text-left hover:bg-secondary",
                kb.id === selected?.id && "bg-accent text-accent-foreground",
              )}
            >
              {kb.name}
            </button>
          ))}
        </nav>
        <form onSubmit={submit} className="flex gap-2">
          <label htmlFor="new-knowledge-base" className="sr-only">
            新知识库名称
          </label>
          <input
            id="new-knowledge-base"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="新知识库名称"
            className="h-9 min-w-0 flex-1 rounded-md border border-input bg-card px-3 text-sm outline-none focus:ring-2 focus:ring-ring"
          />
          <Button type="submit" size="sm" variant="outline" disabled={create.isPending}>
            <Plus aria-hidden="true" className="size-4" />
            新建
          </Button>
        </form>
        {create.isError && <p className="text-sm text-destructive">{create.error.message}</p>}
      </aside>
      {selected && <KnowledgeBaseDetail key={selected.id} knowledgeBase={selected} />}
    </main>
  );
}

type Upload = { key: string; filename: string; message: string | null };

function KnowledgeBaseDetail({ knowledgeBase }: { knowledgeBase: KnowledgeBase }) {
  const queryClient = useQueryClient();
  const documentsKey = ["knowledge-bases", knowledgeBase.id, "documents"];
  const documents = useQuery({ queryKey: documentsKey, queryFn: () => fetchDocuments(knowledgeBase.id) });
  // 正在上传和被拒收的文件；上传成功的从这里移走，出现在文档列表里。
  const [uploads, setUploads] = useState<Upload[]>([]);
  const [dragging, setDragging] = useState(false);

  async function uploadAll(files: File[]) {
    const batch = files.map((file) => ({ key: `${Date.now()}-${file.name}`, filename: file.name, message: null }));
    setUploads((current) => [...current, ...batch]);
    // 后端同步解析入库，逐个上传，避免同时占满模型调用。
    for (const [index, file] of files.entries()) {
      const key = batch[index]!.key;
      try {
        await uploadDocument(knowledgeBase.id, file);
        setUploads((current) => current.filter((upload) => upload.key !== key));
        await queryClient.invalidateQueries({ queryKey: documentsKey });
      } catch (e) {
        const message = e instanceof Error ? e.message : "上传失败";
        setUploads((current) => current.map((upload) => (upload.key === key ? { ...upload, message } : upload)));
      }
    }
  }

  function drop(event: DragEvent) {
    event.preventDefault();
    setDragging(false);
    void uploadAll([...event.dataTransfer.files]);
  }

  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-lg font-medium">{knowledgeBase.name}</h2>
      <div
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={drop}
        className={cn(
          "flex flex-col items-center gap-2 rounded-lg border-2 border-dashed border-input bg-card p-8 text-center",
          dragging && "border-primary bg-accent",
        )}
      >
        <Upload aria-hidden="true" className="size-6 text-muted-foreground" />
        <p>把文件拖到这里，或</p>
        <Button asChild variant="outline" size="sm">
          <label>
            选择文件
            <input
              type="file"
              multiple
              accept=".txt,.md,.markdown"
              className="sr-only"
              onChange={(event) => {
                void uploadAll([...(event.target.files ?? [])]);
                event.target.value = "";
              }}
            />
          </label>
        </Button>
        <p className="text-sm text-muted-foreground">目前支持 TXT 和 Markdown，单个文件不超过 50 MB。</p>
      </div>
      {uploads.length > 0 && (
        <ul aria-label="上传中" className="flex flex-col gap-1 text-sm">
          {uploads.map((upload) =>
            upload.message ? (
              <li key={upload.key} role="alert" className="text-destructive">
                {upload.filename}：{upload.message}
              </li>
            ) : (
              <li key={upload.key} className="text-muted-foreground">
                {upload.filename}：上传并解析中…
              </li>
            ),
          )}
        </ul>
      )}
      {documents.isError && <p className="text-destructive">{documents.error.message}</p>}
      {documents.data?.length === 0 && <p className="text-muted-foreground">还没有文档，先上传一份吧。</p>}
      {documents.data && documents.data.length > 0 && (
        <ul aria-label="文档" className="flex flex-col divide-y rounded-lg border bg-card">
          {documents.data.map((document) => (
            <DocumentRow key={document.id} document={document} />
          ))}
        </ul>
      )}
    </section>
  );
}

const STATUS: Record<KnowledgeDocument["status"], { label: string; className: string }> = {
  READY: { label: "可用", className: "text-success" },
  FAILED: { label: "失败", className: "text-destructive" },
  PARSING: { label: "解析中", className: "text-muted-foreground" },
};

function DocumentRow({ document }: { document: KnowledgeDocument }) {
  const status = STATUS[document.status];
  return (
    <li className="flex items-start gap-3 p-3">
      <FileText aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="truncate font-medium">{document.filename}</span>
        <span className="text-sm text-muted-foreground">
          {document.status === "READY" && `${document.chunkCount} 个分块 · `}
          {formatSize(document.sizeBytes)}
        </span>
        {document.error && <span className="text-sm text-destructive">{document.error}</span>}
      </div>
      <span className={cn("shrink-0 text-sm font-medium", status.className)}>{status.label}</span>
    </li>
  );
}

function formatSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
