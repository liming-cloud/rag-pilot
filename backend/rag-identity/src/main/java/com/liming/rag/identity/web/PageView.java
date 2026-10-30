package com.liming.rag.identity.web;

import com.liming.rag.identity.application.Paged;
import java.util.List;
import java.util.function.Function;

/** 分页响应（D-039）：{@code {items, total, page, size}}。 */
record PageView<T>(List<T> items, long total, int page, int size) {

    static <S, T> PageView<T> of(Paged<S> paged, Function<S, T> mapper) {
        return new PageView<>(paged.items().stream().map(mapper).toList(), paged.total(), paged.page(), paged.size());
    }
}
