package com.liming.rag.settings.application.model;

import com.liming.rag.ai.ModelUsage;
import java.util.function.Supplier;

/**
 * 各用途保存的路由的缓存（D-053、D-054），按用途一份。怎么缓存、存多久由实现决定；保存用途、改或删服务商后由用例
 * 清除全部，在事务里清除的等提交后才生效。
 */
public interface ModelRouteCache {

    /** 有缓存时直接返回，没有时调用 {@code loader} 并缓存结果。 */
    SavedRoute get(ModelUsage usage, Supplier<SavedRoute> loader);

    void forgetAll();
}
