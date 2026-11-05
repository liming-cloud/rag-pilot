/**
 * 缓存平台组件（D-053）：缓存能力的防腐层，做法同 rag-ai。上下文只看到 {@link com.liming.rag.cache.Caches}、
 * {@link com.liming.rag.cache.TypedCache} 和 {@link com.liming.rag.cache.CacheSpec}，看不到 Redis 的类型。
 *
 * <p>机制都在这里：值存 JSON；读不到时加载再写入；在事务里清除的等提交后再清；Redis 出错或超时记
 * {@code event=cache_failure} 后直接读库，一段时间内不再访问 Redis，恢复后先清掉全部缓存再用（出错期间可能漏掉了清除）；
 * 启动后第一次访问前也清一次（升级时的迁移可能改了库）。
 * 缓存什么、何时清除由上下文决定。标记为开放模块，供上下文和 rag-ai 直接使用。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package com.liming.rag.cache;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
