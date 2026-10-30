import type { Page } from "@/lib/api";

/** 假接口的分页：按 page、size 参数切片，与后端一致（size 默认 20、最多 100）。 */
export function paginate<T>(items: T[], url: URL): Page<T> {
  const page = Math.max(1, Number(url.searchParams.get("page")) || 1);
  const size = Math.min(100, Math.max(1, Number(url.searchParams.get("size")) || 20));
  return { items: items.slice((page - 1) * size, page * size), total: items.length, page, size };
}

/** 关键词匹配：任一字段包含关键词（不区分大小写）。 */
export function matches(keyword: string | null, ...fields: Array<string | null | undefined>): boolean {
  if (!keyword) return true;
  const needle = keyword.toLowerCase();
  return fields.some((field) => field?.toLowerCase().includes(needle));
}
