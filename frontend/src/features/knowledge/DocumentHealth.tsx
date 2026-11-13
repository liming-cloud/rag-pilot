import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { FINDINGS, type KnowledgeDocument } from "./api";

/**
 * 资料体检（#218）：每个问题一个标签，点开看整改建议；重复的列出另一份文档和所在的库（只有查看人能访问的库）。
 * 只是提示，不影响入库和检索；解析中、失败的文档还没有结论。
 */
export function DocumentHealth({ document }: { document: KnowledgeDocument }) {
  const [open, setOpen] = useState(false);
  if (document.findings === null) {
    return <span className="text-muted-foreground">—</span>;
  }
  if (document.findings.length === 0) {
    return <Badge variant="success">正常</Badge>;
  }
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <div className="flex max-w-xs min-w-40 flex-wrap gap-1">
        {document.findings.map((finding) => (
          <Button
            key={finding.code}
            size="sm"
            variant="ghost"
            className="h-auto p-0"
            aria-label={`${FINDINGS[finding.code].label}，查看建议`}
            onClick={() => setOpen(true)}
          >
            <Badge>{FINDINGS[finding.code].label}</Badge>
          </Button>
        ))}
      </div>
      {open && (
        <DialogContent>
          <DialogHeader>
            <DialogTitle>体检结论</DialogTitle>
            <DialogDescription className="break-all">{document.filename}</DialogDescription>
          </DialogHeader>
          <ul className="flex flex-col gap-3">
            {document.findings.map((finding) => (
              <li key={finding.code} className="flex flex-col items-start gap-1">
                <Badge>{FINDINGS[finding.code].label}</Badge>
                <span className="text-sm">{FINDINGS[finding.code].advice}</span>
                {finding.similar.map((other) => (
                  <span key={other.documentId} className="text-sm wrap-anywhere text-muted-foreground">
                    《{other.filename}》（{other.knowledgeBaseName}）
                  </span>
                ))}
              </li>
            ))}
          </ul>
          <p className="text-xs text-muted-foreground">体检只是提示，不影响入库和检索。</p>
        </DialogContent>
      )}
    </Dialog>
  );
}
