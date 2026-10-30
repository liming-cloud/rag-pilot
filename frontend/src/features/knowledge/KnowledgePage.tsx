import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FileText, Plus, Upload } from "lucide-react";
import { type DragEvent, type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import {
  createKnowledgeBase,
  deleteDocument,
  fetchDocuments,
  fetchKnowledgeBases,
  type KnowledgeBase,
  type KnowledgeDocument,
  uploadDocument,
} from "./api";
import { MembersPanel } from "./MembersPanel";

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
          <Label htmlFor="new-knowledge-base" className="sr-only">
            新知识库名称
          </Label>
          <Input
            id="new-knowledge-base"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="新知识库名称"
            className="flex-1"
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
  const documents = useQuery({
    queryKey: documentsKey,
    queryFn: () => fetchDocuments(knowledgeBase.id),
    // 入库在后台进行：有解析中的文档时每秒刷新一次，全部处理完就停。
    refetchInterval: (query) => (query.state.data?.some((document) => document.status === "PARSING") ? 1000 : false),
  });
  // 正在上传和被拒收的文件；上传成功的从这里移走，出现在文档列表里。
  const [uploads, setUploads] = useState<Upload[]>([]);
  const [dragging, setDragging] = useState(false);

  async function uploadAll(files: File[], replacesDocumentId?: number) {
    const batch = files.map((file) => ({ key: `${Date.now()}-${file.name}`, filename: file.name, message: null }));
    setUploads((current) => [...current, ...batch]);
    // 后端同步解析入库，逐个上传，避免同时占满模型调用。
    for (const [index, file] of files.entries()) {
      const key = batch[index]!.key;
      try {
        await uploadDocument(knowledgeBase.id, file, replacesDocumentId);
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
          <Label>
            选择文件
            <Input
              type="file"
              multiple
              accept=".pdf,.docx,.xlsx,.xls,.txt,.md,.markdown"
              className="sr-only"
              onChange={(event) => {
                void uploadAll([...(event.target.files ?? [])]);
                event.target.value = "";
              }}
            />
          </Label>
        </Button>
        <p className="text-sm text-muted-foreground">
          支持 PDF、Word（.docx）、Excel、TXT 和 Markdown，单个文件不超过 50 MB；扫描件 PDF 暂不支持。
        </p>
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
                {upload.filename}：上传中…
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
            <DocumentRow
              key={document.id}
              document={document}
              replacedName={documents.data.find((other) => other.id === document.replacesDocumentId)?.filename}
              onReplace={(file) => void uploadAll([file], document.id)}
              onDelete={async () => {
                await deleteDocument(knowledgeBase.id, document.id);
                await queryClient.invalidateQueries({ queryKey: documentsKey });
              }}
            />
          ))}
        </ul>
      )}
      <MembersPanel knowledgeBaseId={knowledgeBase.id} />
    </section>
  );
}

const STATUS: Record<KnowledgeDocument["status"], { label: string; className: string }> = {
  READY: { label: "可用", className: "text-success" },
  FAILED: { label: "失败", className: "text-destructive" },
  PARSING: { label: "解析中", className: "text-muted-foreground" },
};

type DocumentRowProps = {
  document: KnowledgeDocument;
  /** 这份文档要替换的旧版本的文件名；不是新版本时为 undefined。 */
  replacedName: string | undefined;
  onReplace: (file: File) => void;
  onDelete: () => Promise<void>;
};

/** 一份文档：状态、分块数、失败原因，以及“上传新版本”和“删除”（行内二次确认）。 */
function DocumentRow({ document, replacedName, onReplace, onDelete }: DocumentRowProps) {
  const status = STATUS[document.status];
  const [confirming, setConfirming] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  async function confirmDelete() {
    try {
      await onDelete();
    } catch (e) {
      setDeleteError(e instanceof Error ? e.message : "删除失败");
      setConfirming(false);
    }
  }

  return (
    <li className="flex items-start gap-3 p-3">
      <FileText aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="truncate font-medium">{document.filename}</span>
        {replacedName && <span className="text-sm text-muted-foreground">替换《{replacedName}》</span>}
        <span className="text-sm text-muted-foreground">
          {document.status === "READY" && `${document.chunkCount} 个分块 · `}
          {formatSize(document.sizeBytes)}
        </span>
        {document.error && <span className="text-sm text-destructive">{document.error}</span>}
        {document.status === "FAILED" && replacedName && (
          <span className="text-sm text-muted-foreground">新版本解析失败，旧版本《{replacedName}》已保留。</span>
        )}
        {deleteError && <span className="text-sm text-destructive">{deleteError}</span>}
      </div>
      <div className="flex shrink-0 flex-col items-end gap-1">
        <span className={cn("text-sm font-medium", status.className)}>{status.label}</span>
        {confirming ? (
          <div className="flex items-center gap-1">
            <span className="text-sm">确定删除？</span>
            <Button size="sm" variant="destructive" onClick={() => void confirmDelete()}>
              确认删除
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirming(false)}>
              取消
            </Button>
          </div>
        ) : (
          <div className="flex items-center gap-1">
            <Button asChild size="sm" variant="ghost">
              <Label>
                上传新版本
                <Input
                  type="file"
                  accept=".pdf,.docx,.xlsx,.xls,.txt,.md,.markdown"
                  aria-label={`上传新版本 ${document.filename}`}
                  className="sr-only"
                  onChange={(event) => {
                    const file = event.target.files?.[0];
                    if (file) {
                      onReplace(file);
                    }
                    event.target.value = "";
                  }}
                />
              </Label>
            </Button>
            <Button
              size="sm"
              variant="ghost"
              aria-label={`删除 ${document.filename}`}
              onClick={() => setConfirming(true)}
            >
              删除
            </Button>
          </div>
        )}
      </div>
    </li>
  );
}

function formatSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
