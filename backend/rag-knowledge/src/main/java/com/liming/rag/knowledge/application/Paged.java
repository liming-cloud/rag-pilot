package com.liming.rag.knowledge.application;

import java.util.List;
import java.util.function.Function;

/**
 * 分页结果（D-039）：第几页（从 1 起）、每页条数、总条数。
 */
public record Paged<T>(List<T> items, long total, int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public <R> Paged<R> map(Function<T, R> mapper) {
        return new Paged<>(items.stream().map(mapper).toList(), total, page, size);
    }

    /** 请求的页码和条数：页码小于 1 按 1，条数默认 20、最多 100。 */
    public record Request(int page, int size) {

        public Request {
            page = Math.max(1, page);
            size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        }

        public long offset() {
            return (long) (page - 1) * size;
        }
    }
}
