package com.liming.rag.cache;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** 存放缓存的地方，键和值都是字符串。生产用 Redis，单元测试用内存实现；出错时抛运行时异常。 */
interface CacheStore {

    @Nullable String get(String key);

    void set(String key, String value, Duration ttl);

    void delete(String key);

    /** 删掉以 {@code prefix} 开头的全部键。 */
    void deleteByPrefix(String prefix);
}
