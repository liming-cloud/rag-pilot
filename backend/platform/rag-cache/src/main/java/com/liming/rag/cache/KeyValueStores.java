package com.liming.rag.cache;

import com.liming.rag.cache.kv.InMemoryKeyValueStores;
import com.liming.rag.cache.kv.KeyValueStore;
import java.time.Clock;

/**
 * 能力：键值存储，存权威数据（如登录会话），背后没有数据库可以回退，不加一级缓存、不降级。
 *
 * <p>用法：{@code stores.store("sessions")} 取一组 {@link KeyValueStore}，再读写键值、设过期时间。同名的共用同一份数据，
 * 各上下文的名字不要重复。
 *
 * <p>失败：名字不合规抛 {@link IllegalArgumentException}；Redis 出错时存取直接抛运行时异常。
 */
public interface KeyValueStores {

    /** @param name 小写字母、数字和连字符，作为键的一段 */
    KeyValueStore store(String name);

    /** 存在内存里的实现，和没有 Redis 时的生产实现相同，用于单元测试；过期按 {@code clock} 计算。 */
    static KeyValueStores inMemory(Clock clock) {
        return InMemoryKeyValueStores.create(clock);
    }
}
