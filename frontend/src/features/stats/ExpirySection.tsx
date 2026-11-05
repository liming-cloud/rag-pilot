import { useQuery } from "@tanstack/react-query";
import { ChevronRight } from "lucide-react";
import { Link } from "react-router";
import { Badge } from "@/components/ui/badge";
import { fetchExpirySummary, knowledgeKeys } from "@/features/knowledge/api";
import { PERMISSIONS, useCan } from "@/lib/permissions";

/** 运营概览上列出的到期资料份数，其余到知识库里看。 */
export const EXPIRY_ROWS = 5;

/**
 * 资料有效期（#124、#125）：有知识库权限时显示即将到期和已失效的文档数，列出最早到期的几份，周报里一起打印。
 * 都没有时不占位置。
 */
export function ExpirySection() {
  const canView = useCan(PERMISSIONS.KB_VIEW);
  const summary = useQuery({
    queryKey: knowledgeKeys.expirySummary,
    queryFn: fetchExpirySummary,
    enabled: canView,
  });
  if (!summary.data || summary.data.expiring + summary.data.expired === 0) return null;
  const { expiring, expired, warningDays, documents } = summary.data;
  const total = expiring + expired;
  return (
    <section
      aria-label="资料有效期"
      className="flex flex-col gap-2 rounded-xl border bg-card shadow-sm p-3 text-sm print:break-inside-avoid"
    >
      <div role="status" className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-medium">资料有效期</span>
        {expiring > 0 && (
          <span>
            {expiring} 份将在 {warningDays} 天内到期
          </span>
        )}
        {expired > 0 && <span className="text-destructive">{expired} 份已失效，不再参与回答</span>}
        <Link to="/admin/knowledge" className="ml-auto inline-flex items-center hover:underline print:hidden">
          去知识库处理
          <ChevronRight aria-hidden="true" className="size-4" />
        </Link>
      </div>
      <ul className="flex flex-col gap-1">
        {documents.slice(0, EXPIRY_ROWS).map((document) => (
          <li key={document.documentId} className="flex flex-wrap items-center gap-x-2">
            <Badge variant={document.validity === "EXPIRED" ? "destructive" : "default"}>
              {document.validity === "EXPIRED" ? "已失效" : "即将到期"}
            </Badge>
            <Link
              to={`/admin/knowledge/${document.knowledgeBaseId}?validity=${document.validity}`}
              className="wrap-anywhere hover:underline"
            >
              {document.filename}
            </Link>
            <span className="text-muted-foreground">
              {document.knowledgeBaseName} · {document.validity === "EXPIRED" ? "失效于" : "到期"} {document.expiresOn}
            </span>
          </li>
        ))}
      </ul>
      {total > EXPIRY_ROWS && (
        <p className="text-xs text-muted-foreground">还有 {total - EXPIRY_ROWS} 份，到知识库里查看。</p>
      )}
    </section>
  );
}
