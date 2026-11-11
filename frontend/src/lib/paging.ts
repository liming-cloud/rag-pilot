import { useState } from "react";

/** 列表每页默认条数，与后端的默认值一致（D-039）。 */
export const PAGE_SIZE = 20;

type Paging = { page?: number; size?: number };

/**
 * 分页列表的筛选条件：从第 1 页、每页 PAGE_SIZE 条开始。change 合并新条件；没给页码时回到第 1 页
 * （改了筛选条件，原来的页码就没有意义了），翻页、改每页条数时用给出的页码。
 */
export function usePagedFilter<F extends Paging>(initial: Omit<F, keyof Paging>) {
  const [filter, setFilter] = useState<F>(() => ({ ...initial, page: 1, size: PAGE_SIZE }) as F);
  const change = (next: Partial<F>) => setFilter((current) => ({ ...current, ...next, page: next.page ?? 1 }));
  return [filter, change] as const;
}
