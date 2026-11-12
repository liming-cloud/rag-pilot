import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderUp, Search, Upload } from "lucide-react";
import { type DragEvent, type FormEvent, useState } from "react";
import { useSearchParams } from "react-router";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { usePagedFilter } from "@/lib/paging";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import { DocumentRow } from "./DocumentRow";
import { UploadBatch } from "./UploadBatch";
import { ReplaceDialog, ValidityDialog } from "./ValidityDialog";
import {
  deleteDocument,
  type DocumentFilter,
  fetchDocuments,
  type KnowledgeDocument,
  knowledgeKeys,
  reprocessDocument,
  uploadDocument,
  type Validity,
  type ValidityRange,
} from "./api";
import { ACCEPT, type BatchItem, MAX_BATCH, prepare, run } from "./batchUpload";

/** 一批上传：普通上传，或某份文档的新版本（带上有效期）。重试时沿用同样的参数。 */
type Batch = { items: BatchItem[]; replacesDocumentId?: number; validity?: ValidityRange };

const VALIDITY_FILTERS = ["EXPIRING", "EXPIRED", "PENDING"] as const;

/**
 * 知识库的文档（D-039）：顶部上传（选文件、选文件夹或拖到表格上），按文件名、状态、有效期筛选，分页表格。
 * 一批最多 200 个文件、同时传 3 个，结束后汇总成功、重复、不支持、失败，失败的可以只重试这些（#124）。
 * 入库在后台进行：当前页有解析中的文档时每秒刷新一次，全部处理完就停。
 * 地址里的 validity 参数（如从知识库列表的“即将到期”点进来）作为初始筛选。
 */
export function DocumentsPanel({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const initialValidity = VALIDITY_FILTERS.find((value) => value === params.get("validity")) ?? null;
  const [filter, change] = usePagedFilter<DocumentFilter>({ validity: initialValidity });
  const [keyword, setKeyword] = useState("");
  const documents = useQuery({
    queryKey: knowledgeKeys.documents(knowledgeBaseId, filter),
    queryFn: () => fetchDocuments(knowledgeBaseId, filter),
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.items.some((document) => document.status === "PARSING") ? 1000 : false,
  });
  const [batch, setBatch] = useState<Batch | null>(null);
  const [tooMany, setTooMany] = useState<number | null>(null);
  const [dragging, setDragging] = useState(false);
  const [dialog, setDialog] = useState<{ kind: "validity" | "replace"; document: KnowledgeDocument } | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: knowledgeKeys.all });

  async function start(next: Batch) {
    setBatch(next);
    await run(
      next.items,
      async (file) => {
        await uploadDocument(knowledgeBaseId, file, next.replacesDocumentId, next.validity);
        await refresh();
      },
      (key, change) =>
        setBatch(
          (current) =>
            current && {
              ...current,
              items: current.items.map((item) => (item.key === key ? { ...item, ...change } : item)),
            },
        ),
    );
  }

  function uploadAll(files: File[]) {
    const items = prepare(files);
    if (items.length === 0) return;
    if (items.length > MAX_BATCH) {
      setTooMany(items.length);
      return;
    }
    setTooMany(null);
    void start({ items });
  }

  function retry() {
    if (!batch) return;
    void start({
      ...batch,
      items: batch.items.map((item) =>
        item.outcome === "failed" ? { ...item, outcome: "pending", message: null } : item,
      ),
    });
  }

  function drop(event: DragEvent) {
    event.preventDefault();
    setDragging(false);
    uploadAll([...event.dataTransfer.files]);
  }

  function search(event: FormEvent) {
    event.preventDefault();
    change({ keyword: keyword.trim() });
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
                change({ status: (event.target.value || null) as DocumentFilter["status"] })
              }
            >
              <NativeSelectOption value="">全部状态</NativeSelectOption>
              <NativeSelectOption value="READY">可用</NativeSelectOption>
              <NativeSelectOption value="PARSING">解析中</NativeSelectOption>
              <NativeSelectOption value="FAILED">失败</NativeSelectOption>
            </NativeSelect>
          </Label>
          <Label className="flex flex-col gap-1 text-sm">
            有效期
            <NativeSelect
              value={filter.validity ?? ""}
              onChange={(event) =>
                change({ validity: (event.target.value || null) as Validity | null })
              }
            >
              <NativeSelectOption value="">全部</NativeSelectOption>
              <NativeSelectOption value="EXPIRING">即将到期</NativeSelectOption>
              <NativeSelectOption value="EXPIRED">已失效</NativeSelectOption>
              <NativeSelectOption value="PENDING">未生效</NativeSelectOption>
            </NativeSelect>
          </Label>
          <Button type="submit" size="sm" variant="outline">
            <Search aria-hidden="true" className="size-4" />
            查询
          </Button>
        </form>
        <Can permission={PERMISSIONS.DOC_UPLOAD}>
          <div className="ml-auto flex gap-2">
            <Button asChild size="sm" variant="outline">
              <Label>
                <FolderUp aria-hidden="true" className="size-4" />
                上传文件夹
                <Input
                  type="file"
                  multiple
                  aria-label="选择文件夹"
                  className="sr-only"
                  // 浏览器的文件夹选择：取文件夹里（含子文件夹）的全部文件，不保留目录层级。
                  {...{ webkitdirectory: "" }}
                  onChange={(event) => {
                    uploadAll([...(event.target.files ?? [])]);
                    event.target.value = "";
                  }}
                />
              </Label>
            </Button>
            <Button asChild size="sm">
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
                    uploadAll([...(event.target.files ?? [])]);
                    event.target.value = "";
                  }}
                />
              </Label>
            </Button>
          </div>
        </Can>
      </div>
      <p className="text-xs text-muted-foreground">
        支持 PDF、Word（.docx）、Excel、PowerPoint（.pptx）、TXT 和 Markdown，单个文件不超过 50 MB（课件过大可先压缩图片或另存为 PDF），一次最多 {MAX_BATCH}{" "}
        个文件，也可以把文件拖到下面的表格上；选文件夹时会跳过不支持的格式。扫描件 PDF 暂不支持。
      </p>
      {tooMany !== null && (
        <p role="alert" className="text-sm text-destructive">
          一次最多上传 {MAX_BATCH} 个文件，这次选了 {tooMany} 个，请分批选择。
        </p>
      )}
      {batch && <UploadBatch items={batch.items} onRetry={retry} onClose={() => setBatch(null)} />}
      <ErrorMessage error={documents.error} className="text-base" />
      <div
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={drop}
        className={cn("overflow-x-auto rounded-xl border bg-card shadow-sm", dragging && "border-primary bg-accent")}
      >
        <Table aria-label="文档列表">
          <TableHeader>
            <TableRow>
              <TableHead>文件</TableHead>
              <TableHead>状态</TableHead>
              <TableHead className="text-right">分块</TableHead>
              <TableHead className="text-right">大小</TableHead>
              <TableHead>上传时间</TableHead>
              <TableHead>有效期</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {documents.data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="py-10 text-center text-muted-foreground">
                  {filter.keyword || filter.status || filter.validity
                    ? "没有符合条件的文档"
                    : "还没有文档，先上传一份吧。"}
                </TableCell>
              </TableRow>
            )}
            {documents.data?.items.map((document) => (
              <DocumentRow
                key={document.id}
                document={document}
                onValidity={() => setDialog({ kind: "validity", document })}
                onReplace={() => setDialog({ kind: "replace", document })}
                onDelete={async () => {
                  await deleteDocument(knowledgeBaseId, document.id);
                  await refresh();
                }}
                onReprocess={async () => {
                  await reprocessDocument(knowledgeBaseId, document.id);
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
          onChange={change}
        />
      )}
      <Dialog open={dialog !== null} onOpenChange={(open) => !open && setDialog(null)}>
        {dialog?.kind === "validity" && (
          <ValidityDialog knowledgeBaseId={knowledgeBaseId} document={dialog.document} onDone={() => setDialog(null)} />
        )}
        {dialog?.kind === "replace" && (
          <ReplaceDialog
            document={dialog.document}
            onSubmit={(file, validity) =>
              void start({ items: prepare([file]), replacesDocumentId: dialog.document.id, validity })
            }
            onDone={() => setDialog(null)}
          />
        )}
      </Dialog>
    </section>
  );
}
