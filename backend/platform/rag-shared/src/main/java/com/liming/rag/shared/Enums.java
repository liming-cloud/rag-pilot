package com.liming.rag.shared;

import java.util.Locale;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** 把接口传来的字符串解析成枚举：不区分大小写，不认识时抛调用方给的业务异常（说明由各模块写）。 */
public final class Enums {

    private Enums() {
    }

    /** 必填：没填或不认识时抛 {@code invalid} 给的异常。 */
    public static <E extends Enum<E>> E parse(Class<E> type, @Nullable String value,
            Supplier<? extends BusinessException> invalid) {
        if (value == null) {
            throw invalid.get();
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid.get();
        }
    }

    /** 可选：没填（null 或空白）时为 null，填了不认识的抛 {@code invalid} 给的异常。 */
    public static <E extends Enum<E>> @Nullable E parseOrNull(Class<E> type, @Nullable String value,
            Supplier<? extends BusinessException> invalid) {
        return value == null || value.isBlank() ? null : parse(type, value, invalid);
    }
}
