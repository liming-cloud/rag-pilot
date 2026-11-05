package com.liming.rag.shared;

import org.jspecify.annotations.Nullable;

/** 接口传来的文字的通用处理。 */
public final class Texts {

    private Texts() {
    }

    /** 没填（null、空串或只有空白）时为 null，否则去掉首尾空白。 */
    public static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
