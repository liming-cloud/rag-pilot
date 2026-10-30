import { ChevronLeft, ChevronRight } from "lucide-react";
import { type FormEvent, useState } from "react";
import { cn } from "@/lib/utils";
import { Button } from "./button";
import { Input } from "./input";
import { NativeSelect, NativeSelectOption } from "./native-select";

export const PAGE_SIZES = [10, 20, 50, 100] as const;

/** 要显示的页码：首页、末页、当前页前后各一页，中间断开处用省略号（null）。 */
export function pageItems(page: number, pages: number): (number | null)[] {
  if (pages <= 7) return Array.from({ length: pages }, (_, index) => index + 1);
  const shown = new Set([1, pages, page - 1, page, page + 1].filter((value) => value >= 1 && value <= pages));
  // 靠近两端时多显示几页，让页码数量稳定。
  if (page <= 3) [2, 3, 4, 5].forEach((value) => shown.add(value));
  if (page >= pages - 2) [pages - 4, pages - 3, pages - 2, pages - 1].forEach((value) => shown.add(value));
  const sorted = [...shown].sort((a, b) => a - b);
  return sorted.flatMap((value, index) => (index > 0 && value - sorted[index - 1]! > 1 ? [null, value] : [value]));
}

// 列表分页（按 shadcn/ui Pagination 的结构）：共几条、每页条数、上一页、页码、下一页、跳至某页。分页由后端做；
// 改每页条数时回到第 1 页。
export function Pagination({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (next: { page: number; size: number }) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  const [jump, setJump] = useState("");
  const go = (target: number) => onChange({ page: Math.min(Math.max(1, target), pages), size });

  function submitJump(event: FormEvent) {
    event.preventDefault();
    const target = Number(jump);
    if (Number.isInteger(target) && target > 0) go(target);
    setJump("");
  }

  return (
    <nav aria-label="分页" className="flex flex-wrap items-center justify-end gap-2 text-sm text-muted-foreground">
      <span>共 {total} 条</span>
      <NativeSelect
        aria-label="每页条数"
        value={size}
        className="h-8"
        onChange={(event) => onChange({ page: 1, size: Number(event.target.value) })}
      >
        {PAGE_SIZES.map((option) => (
          <NativeSelectOption key={option} value={option}>
            {option} 条/页
          </NativeSelectOption>
        ))}
      </NativeSelect>
      <Button variant="ghost" size="sm" aria-label="上一页" disabled={page <= 1} onClick={() => go(page - 1)}>
        <ChevronLeft aria-hidden="true" className="size-4" />
      </Button>
      {pageItems(page, pages).map((item, index) =>
        item === null ? (
          <span key={`gap-${index}`} aria-hidden="true" className="px-1">
            …
          </span>
        ) : (
          <Button
            key={item}
            variant={item === page ? "outline" : "ghost"}
            size="sm"
            aria-label={`第 ${item} 页`}
            aria-current={item === page ? "page" : undefined}
            className={cn("min-w-8 px-2", item === page && "font-medium text-foreground")}
            onClick={() => item !== page && go(item)}
          >
            {item}
          </Button>
        ),
      )}
      <Button variant="ghost" size="sm" aria-label="下一页" disabled={page >= pages} onClick={() => go(page + 1)}>
        <ChevronRight aria-hidden="true" className="size-4" />
      </Button>
      <form onSubmit={submitJump} className="flex items-center gap-1">
        跳至
        <Input
          aria-label="跳转到页码"
          inputMode="numeric"
          value={jump}
          className="h-8 w-14 text-center"
          onChange={(event) => setJump(event.target.value.replace(/\D/g, ""))}
        />
        页
      </form>
    </nav>
  );
}
