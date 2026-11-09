package com.liming.rag.cache.typed;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * 存放缓存的地方，键和值都是字符串。生产用 Redis，单元测试用内存实现；出错时抛运行时异常。
 *
 * <p>防止旧值写回（D-053）：清除前先把代数加一；读不到时先记下代数再加载，写回时代数没变才写。这样“加载时读到旧数据、
 * 清除之后才写回”的那次写入会被拒绝。比较和写入、加一和删除都要是原子的。
 */
interface CacheStore {

    @Nullable String get(String key);

    /** 当前代数，没有时为 {@code "0"}。 */
    String generation(String generationKey);

    /** 代数仍是 {@code expected} 时写入，返回是否写入。 */
    boolean setIfGeneration(String key, String value, Duration ttl, String generationKey, String expected);

    /** 代数加一，再删掉这个键。 */
    void bumpAndDelete(String generationKey, String key);

    /** 代数加一，再删掉以 {@code prefix} 开头的全部键。 */
    void bumpAndDeleteByPrefix(String generationKey, String prefix);
}
