import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PAGE_SIZE, usePagedFilter } from "./paging";

type Filter = { keyword?: string; days: number; page: number; size: number };

describe("usePagedFilter", () => {
  it("从第 1 页、每页 PAGE_SIZE 条开始，带上初始条件", () => {
    const { result } = renderHook(() => usePagedFilter<Filter>({ days: 30 }));
    expect(result.current[0]).toEqual({ days: 30, page: 1, size: PAGE_SIZE });
  });

  it("改筛选条件回到第 1 页，其余条件保留", () => {
    const { result } = renderHook(() => usePagedFilter<Filter>({ days: 30 }));
    act(() => result.current[1]({ page: 3 }));
    act(() => result.current[1]({ keyword: "交班" }));
    expect(result.current[0]).toEqual({ days: 30, keyword: "交班", page: 1, size: PAGE_SIZE });
  });

  it("翻页、改每页条数时用给出的页码", () => {
    const { result } = renderHook(() => usePagedFilter<Filter>({ days: 7 }));
    act(() => result.current[1]({ page: 2, size: 50 }));
    expect(result.current[0]).toEqual({ days: 7, page: 2, size: 50 });
  });

  it("同一轮里连着改两次，后一次在前一次的基础上改", () => {
    const { result } = renderHook(() => usePagedFilter<Filter>({ days: 30 }));
    act(() => {
      result.current[1]({ keyword: "交班" });
      result.current[1]({ days: 7 });
    });
    expect(result.current[0]).toEqual({ days: 7, keyword: "交班", page: 1, size: PAGE_SIZE });
  });
});
