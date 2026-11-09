package com.liming.rag.cache;

import com.liming.rag.cache.typed.CacheSpec;
import com.liming.rag.cache.typed.InMemoryCaches;
import com.liming.rag.cache.typed.TypedCache;

/**
 * 能力：业务缓存，加速查询。读的顺序是一级（进程内）→ 二级（Redis，按配置）→ 加载；在事务里清除的等提交后再清。
 *
 * <p>用法：上下文在 {@code infrastructure/cache} 里 {@code caches.cache(new CacheSpec<>("名字", Xxx.class))} 取
 * {@link TypedCache}，{@code get(key, loader)} 读、{@code evict}/{@code clear} 清除。同名的缓存共用同一份数据，各上下文的
 * 缓存名不要重复。
 *
 * <p>失败：Redis 出错只记日志并降级，不抛给调用方；{@code loader} 抛出的异常原样抛出。
 */
public interface Caches {

    <V> TypedCache<V> cache(CacheSpec<V> spec);

    /**
     * 只有一级缓存的实现，和没有 Redis 时的生产实现相同（提交后清除），用于单元测试：漏了清除时测试能发现。
     */
    static Caches inMemory() {
        return InMemoryCaches.create();
    }
}
