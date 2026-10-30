import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "./button";

// 列表分页（按 shadcn/ui Pagination 的结构简化）：共几条、第几页，上一页、下一页。分页由后端做。
export function Pagination({
  page,
  size,
  total,
  onPageChange,
}: {
  page: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <nav aria-label="分页" className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
      <span>
        共 {total} 条 · 第 {page} / {pages} 页
      </span>
      <Button
        variant="outline"
        size="sm"
        aria-label="上一页"
        disabled={page <= 1}
        onClick={() => onPageChange(page - 1)}
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
      </Button>
      <Button
        variant="outline"
        size="sm"
        aria-label="下一页"
        disabled={page >= pages}
        onClick={() => onPageChange(page + 1)}
      >
        <ChevronRight aria-hidden="true" className="size-4" />
      </Button>
    </nav>
  );
}
