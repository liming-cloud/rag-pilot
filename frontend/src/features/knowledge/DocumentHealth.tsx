import { Badge } from "@/components/ui/badge";
import { FINDINGS, type KnowledgeDocument } from "./api";

/**
 * 资料体检（#218）：每个问题一个标签加一句整改建议；重复的列出另一份文档和所在的库（只有查看人能访问的库）。
 * 只是提示，不影响入库和检索；解析中、失败的文档还没有结论。
 */
export function DocumentHealth({ document }: { document: KnowledgeDocument }) {
  if (document.findings === null) {
    return <span className="text-muted-foreground">—</span>;
  }
  if (document.findings.length === 0) {
    return <Badge variant="success">正常</Badge>;
  }
  return (
    <ul className="flex max-w-xs min-w-48 flex-col gap-1.5">
      {document.findings.map((finding) => (
        <li key={finding.code} className="flex flex-col items-start gap-0.5">
          <Badge>{FINDINGS[finding.code].label}</Badge>
          <span className="text-xs text-muted-foreground">{FINDINGS[finding.code].advice}</span>
          {finding.similar.map((other) => (
            <span key={other.documentId} className="text-xs wrap-anywhere">
              《{other.filename}》（{other.knowledgeBaseName}）
            </span>
          ))}
        </li>
      ))}
    </ul>
  );
}
