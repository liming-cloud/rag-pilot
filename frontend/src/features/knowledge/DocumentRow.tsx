import { Pencil } from "lucide-react";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { TableCell, TableRow } from "@/components/ui/table";
import { Can, PERMISSIONS, useCan } from "@/lib/permissions";
import { formatDateTime } from "@/lib/utils";
import { rangeParts } from "./ValidityDialog";
import { type KnowledgeDocument, VALIDITY } from "./api";

/** 处理状态的显示文字和标签样式。 */
const STATUS = {
  READY: { label: "可用", variant: "success" },
  PARSING: { label: "解析中", variant: "outline" },
  FAILED: { label: "失败", variant: "destructive" },
} as const;

/**
 * 一份文档：状态、分块数、大小、上传时间、失败原因、有效期，以及“上传新版本”和“删除”（行内二次确认）。
 * 有修改权限时点有效期本身就能改，不再单占一个操作按钮，窄屏下操作列放得下。
 */
export function DocumentRow({
  document,
  onValidity,
  onReplace,
  onDelete,
  onReprocess,
}: {
  document: KnowledgeDocument;
  onValidity: () => void;
  onReplace: () => void;
  onDelete: () => Promise<void>;
  onReprocess: () => Promise<void>;
}) {
  const canUpdate = useCan(PERMISSIONS.DOC_UPDATE);
  const [confirming, setConfirming] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [reprocessing, setReprocessing] = useState(false);
  const status = STATUS[document.status];

  async function reprocess() {
    setReprocessing(true);
    setDeleteError(null);
    try {
      await onReprocess();
    } catch (e) {
      setDeleteError(e instanceof Error ? e.message : "重新处理失败");
    } finally {
      setReprocessing(false);
    }
  }

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
      <TableCell className="max-w-md min-w-56">
        <div className="flex flex-col gap-0.5">
          <span className="font-medium wrap-anywhere">{document.filename}</span>
          {document.replacesFilename && (
            <span className="text-xs text-muted-foreground">替换《{document.replacesFilename}》</span>
          )}
          {document.error && <span className="text-xs text-destructive">{document.error}</span>}
          {document.status === "FAILED" && <FailureHint document={document} />}
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
      </TableCell>
      <TableCell className="text-right tabular-nums">{document.status === "READY" ? document.chunkCount : "—"}</TableCell>
      <TableCell className="text-right whitespace-nowrap tabular-nums">{formatSize(document.sizeBytes)}</TableCell>
      <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">
        {document.createdAt ? formatDateTime(document.createdAt) : "—"}
      </TableCell>
      <TableCell>
        <div className="flex flex-col items-start gap-0.5">
          {canUpdate ? (
            <Button
              size="sm"
              variant="ghost"
              className="-ml-2 h-auto min-h-7 justify-start px-2 text-left font-normal whitespace-normal text-muted-foreground"
              aria-label={`有效期 ${document.filename}`}
              onClick={onValidity}
            >
              <Range document={document} />
              <Pencil aria-hidden="true" className="size-3" />
            </Button>
          ) : (
            <span className="text-sm text-muted-foreground">
              <Range document={document} />
            </span>
          )}
          {document.validity !== "ACTIVE" && (
            <Badge variant={VALIDITY[document.validity].variant}>{VALIDITY[document.validity].label}</Badge>
          )}
        </div>
      </TableCell>
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
          <div className="flex flex-wrap items-center justify-end gap-1">
            {document.status === "FAILED" && document.reprocessable && (
              <Can permission={PERMISSIONS.DOC_UPLOAD}>
                <Button
                  size="sm"
                  variant="ghost"
                  aria-label={`重新处理 ${document.filename}`}
                  disabled={reprocessing}
                  onClick={() => void reprocess()}
                >
                  重新处理
                </Button>
              </Can>
            )}
            <Can permission={PERMISSIONS.DOC_REPLACE}>
              <Button size="sm" variant="ghost" aria-label={`上传新版本 ${document.filename}`} onClick={onReplace}>
                上传新版本
              </Button>
            </Can>
            <Can permission={PERMISSIONS.DOC_DELETE}>
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

/**
 * 失败了怎么办（#145 A9）：模型暂时不可用的可以重新处理，第一次失败 10 分钟后自动重试一次；
 * 文件本身的问题要改好文件重新上传，同一份文件会替换这条失败记录。
 */
function FailureHint({ document }: { document: KnowledgeDocument }) {
  if (!document.reprocessable) {
    return <span className="text-xs text-muted-foreground">改好文件后重新上传，会替换这条失败记录。</span>;
  }
  const at = document.retryAt ? formatDateTime(document.retryAt) : null;
  return (
    <span className="text-xs text-muted-foreground">
      {at ? `模型服务恢复后就能入库，${at} 会自动重试一次，也可以现在重新处理。` : "模型服务恢复后点“重新处理”。"}
    </span>
  );
}

export function Range({ document }: { document: KnowledgeDocument }) {
  return (
    <span className="flex flex-col">
      {rangeParts(document).map((part) => (
        <span key={part} className="whitespace-nowrap">
          {part}
        </span>
      ))}
    </span>
  );
}

export function formatSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
