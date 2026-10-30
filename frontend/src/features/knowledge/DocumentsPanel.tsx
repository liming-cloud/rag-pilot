import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search, Upload } from "lucide-react";
import { type DragEvent, type FormEvent, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Can } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import { deleteDocument, type DocumentFilter, fetchDocuments, type KnowledgeDocument, uploadDocument } from "./api";

const PAGE_SIZE = 20;
const ACCEPT = ".pdf,.docx,.xlsx,.xls,.txt,.md,.markdown";

type Uploading = { key: string; filename: string; message: string | null };

const STATUS = {
  READY: { label: "可用", variant: "success" },
  PARSING: { label: "解析中", variant: "outline" },
  FAILED: { label: "失败", variant: "destructive" },
} as const;

/**
 * 知识库的文档（D-039）：顶部上传（按钮或拖到表格上），按文件名、状态筛选，分页表格。
 * 入库在后台进行：当前页有解析中的文档时每秒刷新一次，全部处理完就停。
 */
export function DocumentsPanel({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<DocumentFilter>({ page: 1, size: PAGE_SIZE });
  const [keyword, setKeyword] = useState("");
  const documents = useQuery({
    queryKey: ["knowledge-bases", knowledgeBaseId, "documents", filter],
    queryFn: () => fetchDocuments(knowledgeBaseId, filter),
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.items.some((document) => document.status === "PARSING") ? 1000 : false,
  });
  const [uploads, setUploads] = useState<Uploading[]>([]);
  const [dragging, setDragging] = useState(false);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["knowledge-bases"] });

  async function uploadAll(files: File[], replacesDocumentId?: number) {
    const batch = files.map((file) => ({ key: `${Date.now()}-${file.name}`, filename: file.name, message: null }));
    setUploads((current) => [...current, ...batch]);
    // 逐个上传，避免同时占满模型调用。
    for (const [index, file] of files.entries()) {
      const key = batch[index]!.key;
      try {
        await uploadDocument(knowledgeBaseId, file, replacesDocumentId);
        setUploads((current) => current.filter((upload) => upload.key !== key));
        await refresh();
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

  function search(event: FormEvent) {
    event.preventDefault();
    setFilter({ ...filter, keyword: keyword.trim(), page: 1 });
  }

  return (
    <section aria-label="文档" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-2">
        <form role="search" aria-label="筛选文档" onSubmit={search} className="flex flex-wrap items-end gap-2">
          <Label className="flex flex-col gap-1 text-sm">
            文件名
            <Input value={keyword} className="w-56" onChange={(event) => setKeyword(event.target.value)} />
          </Label>
          <Label className="flex flex-col gap-1 text-sm">
            状态
            <NativeSelect
              value={filter.status ?? ""}
              onChange={(event) =>
                setFilter({ ...filter, status: (event.target.value || null) as DocumentFilter["status"], page: 1 })
              }
            >
              <NativeSelectOption value="">全部状态</NativeSelectOption>
              <NativeSelectOption value="READY">可用</NativeSelectOption>
              <NativeSelectOption value="PARSING">解析中</NativeSelectOption>
              <NativeSelectOption value="FAILED">失败</NativeSelectOption>
            </NativeSelect>
          </Label>
          <Button type="submit" size="sm" variant="outline">
            <Search aria-hidden="true" className="size-4" />
            查询
          </Button>
        </form>
        <Can permission="doc:upload">
          <Button asChild size="sm" className="ml-auto">
            <Label>
              <Upload aria-hidden="true" className="size-4" />
              上传文档
              <Input
                type="file"
                multiple
                accept={ACCEPT}
                aria-label="选择文件"
                className="sr-only"
                onChange={(event) => {
                  void uploadAll([...(event.target.files ?? [])]);
                  event.target.value = "";
                }}
              />
            </Label>
          </Button>
        </Can>
      </div>
      <p className="text-xs text-muted-foreground">
        支持 PDF、Word（.docx）、Excel、TXT 和 Markdown，单个文件不超过 50 MB，也可以把文件拖到下面的表格上；扫描件 PDF
        暂不支持。
      </p>
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
      <div
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={drop}
        className={cn("rounded-lg border bg-card", dragging && "border-primary bg-accent")}
      >
        <Table aria-label="文档列表">
          <TableHeader>
            <TableRow>
              <TableHead>文件</TableHead>
              <TableHead>状态</TableHead>
              <TableHead>大小</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {documents.data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={4} className="py-6 text-center text-muted-foreground">
                  {filter.keyword || filter.status ? "没有符合条件的文档" : "还没有文档，先上传一份吧。"}
                </TableCell>
              </TableRow>
            )}
            {documents.data?.items.map((document) => (
              <DocumentRow
                key={document.id}
                document={document}
                onReplace={(file) => void uploadAll([file], document.id)}
                onDelete={async () => {
                  await deleteDocument(knowledgeBaseId, document.id);
                  await refresh();
                }}
              />
            ))}
          </TableBody>
        </Table>
      </div>
      {documents.data && (
        <Pagination
          page={documents.data.page}
          size={documents.data.size}
          total={documents.data.total}
          onPageChange={(page) => setFilter({ ...filter, page })}
        />
      )}
    </section>
  );
}

/** 一份文档：状态、分块数、失败原因，以及“上传新版本”和“删除”（行内二次确认）。 */
function DocumentRow({
  document,
  onReplace,
  onDelete,
}: {
  document: KnowledgeDocument;
  onReplace: (file: File) => void;
  onDelete: () => Promise<void>;
}) {
  const [confirming, setConfirming] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const status = STATUS[document.status];

  async function confirmDelete() {
    try {
      await onDelete();
    } catch (e) {
      setDeleteError(e instanceof Error ? e.message : "删除失败");
      setConfirming(false);
    }
  }

  return (
    <TableRow>
      <TableCell className="max-w-md py-2.5">
        <div className="flex flex-col gap-0.5">
          <span className="truncate font-medium">{document.filename}</span>
          {document.replacesFilename && (
            <span className="text-xs text-muted-foreground">替换《{document.replacesFilename}》</span>
          )}
          {document.error && <span className="text-xs text-destructive">{document.error}</span>}
          {document.status === "FAILED" && document.replacesFilename && (
            <span className="text-xs text-muted-foreground">
              新版本解析失败，旧版本《{document.replacesFilename}》已保留。
            </span>
          )}
          {deleteError && <span className="text-xs text-destructive">{deleteError}</span>}
        </div>
      </TableCell>
      <TableCell>
        <Badge variant={status.variant}>{status.label}</Badge>
        {document.status === "READY" && (
          <span className="ml-2 text-xs text-muted-foreground">{document.chunkCount} 个分块</span>
        )}
      </TableCell>
      <TableCell className="text-muted-foreground">{formatSize(document.sizeBytes)}</TableCell>
      <TableCell className="text-right">
        {confirming ? (
          <div className="flex items-center justify-end gap-1">
            <span className="text-sm">确定删除？</span>
            <Button size="sm" variant="destructive" onClick={() => void confirmDelete()}>
              确认删除
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirming(false)}>
              取消
            </Button>
          </div>
        ) : (
          <div className="flex items-center justify-end gap-1">
            <Can permission="doc:replace">
              <Button asChild size="sm" variant="ghost">
                <Label>
                  上传新版本
                  <Input
                    type="file"
                    accept={ACCEPT}
                    aria-label={`上传新版本 ${document.filename}`}
                    className="sr-only"
                    onChange={(event) => {
                      const file = event.target.files?.[0];
                      if (file) onReplace(file);
                      event.target.value = "";
                    }}
                  />
                </Label>
              </Button>
            </Can>
            <Can permission="doc:delete">
              <Button
                size="sm"
                variant="ghost"
                aria-label={`删除 ${document.filename}`}
                onClick={() => setConfirming(true)}
              >
                删除
              </Button>
            </Can>
          </div>
        )}
      </TableCell>
    </TableRow>
  );
}

function formatSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
