package com.liming.rag.persistence;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liming.rag.shared.Paged;
import java.util.function.Function;

/** 单表分页（{@code selectPage}）与 {@link Paged} 之间的转换，只在各上下文的 infrastructure 包里用。 */
public final class Pages {

    private Pages() {
    }

    /** 按请求的页码和条数（已由 {@link Paged.Request} 归一）建 MyBatis-Plus 的分页参数。 */
    public static <T> Page<T> of(Paged.Request request) {
        return new Page<>(request.page(), request.size());
    }

    /** 查到的一页换成 {@link Paged}，每行按 {@code mapper} 转换；页码和条数沿用请求的值。 */
    public static <T, R> Paged<R> toPaged(IPage<T> found, Paged.Request request, Function<T, R> mapper) {
        return new Paged<>(found.getRecords().stream().map(mapper).toList(), found.getTotal(), request.page(),
                request.size());
    }
}
