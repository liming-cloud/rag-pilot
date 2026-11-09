package com.liming.rag.cache.typed;

import org.springframework.modulith.NamedInterface;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * 一种缓存的读和清除。缓存出错时不抛异常：读直接走加载，清除只记日志（恢复后组件会清掉全部缓存）。
 */
@NamedInterface("typed")
public interface TypedCache<V> {

    /**
     * 有缓存时直接返回；没有时调用 {@code loader}，结果不为 null 时写入缓存。null 不缓存，下次照样加载。
     *
     * @param key 本缓存内的键，不含用户输入的原文（需要时先摘要）
     */
    @Nullable V get(String key, Supplier<? extends @Nullable V> loader);

    /** 清除一个键。在事务里调用时等事务提交后再清，回滚时不清。 */
    void evict(String key);

    /** 清除本缓存的全部键，时机同 {@link #evict(String)}。 */
    void clear();
}
