import { Pencil } from "lucide-react";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { TableCell, TableRow } from "@/components/ui/table";
import { Can, PERMISSIONS, useCan } from "@/lib/permissions";
import { type KnowledgeDocument, VALIDITY } from "./api";
import { rangeParts } from "./ValidityDialog";

/** 处理状态的显示文字和标签样式。 */
const STATUS = {
  READY: { label: "可用", variant: "success" },
  PARSING: { label: "解析中", variant: "outline" },
  FAILED: { label: "失败", variant: "destructive" },
} as const;

/**
 * 一份文档：状态、分块数、失败原因、有效期，以及“上传新版本”和“删除”（行内二次确认）。
 * 有修改权限时点有效期本身就能改，不再单占一个操作按钮，窄屏下操作列放得下。
 */
export function DocumentRow({
  document,
  onValidity,
  onReplace,
  onDelete,
}: {
  document: KnowledgeDocument;
  onValidity: () => void;
  onReplace: () => void;
  onDelete: () => Promise<void>;
}) {
  const canUpdate = useCan(PERMISSIONS.DOC_UPDATE);
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
      <TableCell className="max-w-md min-w-40 py-2.5">
        <div className="flex flex-col gap-0.5">
          <span className="font-medium wrap-anywhere">{document.filename}</span>
          <span className="text-xs text-muted-foreground">{formatSize(document.sizeBytes)}</span>
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
        <div className="flex flex-col items-start gap-0.5">
          <Badge variant={status.variant}>{status.label}</Badge>
          {document.status === "READY" && (
            <span className="text-xs whitespace-nowrap text-muted-foreground">{document.chunkCount} 个分块</span>
          )}
        </div>
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
