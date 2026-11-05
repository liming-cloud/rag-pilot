/**
 * 缓存平台组件（D-053、D-054）：对外提供两种能力，上下文只在自己的 {@code infrastructure} 层使用，实现本上下文声明的端口。
 * <ul>
 *   <li>查询加速：{@link com.liming.rag.cache.Caches}、{@link com.liming.rag.cache.TypedCache}、
 *       {@link com.liming.rag.cache.CacheSpec}。读的顺序是一级（进程内）→ 二级（Redis）→ 加载；在事务里清除的等提交后再清；
 *       Redis 出错或超时记 {@code event=cache_failure} 后跳过二级，一段时间内不再访问，恢复后先清掉全部二级缓存再用。</li>
 *   <li>键值存储：{@link com.liming.rag.cache.KeyValueStores}、{@link com.liming.rag.cache.KeyValueStore}，存权威数据（如会话），
 *       不降级。</li>
 * </ul>
 * 用不用 Redis、每种缓存用哪几层、过期时间、容量都在配置 {@code rag.cache} 里，上下文不知道。缓存什么、何时清除由上下文决定。
 * 标记为开放模块，供上下文的 infrastructure 和 rag-ai 使用。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package com.liming.rag.cache;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
